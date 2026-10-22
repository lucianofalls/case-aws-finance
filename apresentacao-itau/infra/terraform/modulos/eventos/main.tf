# Arquitetura de eventos (ADR 025/026, docs/eventos.md):
#
#   Kinesis "transacoes"  ← TransacaoAutorizada/Recusada/EstornoRealizado (até 5k/s, chave id_contrato)
#       ├─ KCL (contabilização, Fargate)        ├─ Lambda antifraude (stub)
#       ├─ Lambda notificações (stub)           └─ Firehose → S3 Object Lock (auditoria + data platform)
#
#   EventBridge "financas" ← LimiteConcedido (ACL), LimiteAlterado, OperacaoRevertida (baixo volume)
#       ├─ regra limite-concedido → SQS limites-concedidos → limites-service
#       └─ regra trilha-dominio   → Firehose → S3 Object Lock
#
# Por que não tudo no EventBridge: em sa-east-1 a quota padrão é PutEvents 600/s e 1.100 invocações de
# destino/s (doc AWS), e custaria ~US$ 1/milhão de eventos. Kinesis provisionado custa ~US$ 0,5 mil/mês
# no volume do PDF, com replay por consumidor. Ordem não é garantida (PutRecords); consumidores não dependem dela.

data "aws_caller_identity" "atual" {}
data "aws_region" "atual" {}

locals {
  filas = {
    limites-concedidos       = { visibilidade = 60, recebimentos_max = 5, atraso = 0 }
    autorizacoes-verificacao = { visibilidade = 120, recebimentos_max = 5, atraso = var.atraso_verificacao_segundos }
    corebanking-contratos    = { visibilidade = 60, recebimentos_max = 3, atraso = 0 }
    reservas-a-expirar       = { visibilidade = 60, recebimentos_max = 10, atraso = 0 } # ADR 035
  }
}

# ---- Kinesis: fluxo transacional de alto volume ---------------------------------------------------------
resource "aws_kinesis_stream" "transacoes" {
  name             = "${var.prefixo}transacoes"
  shard_count      = var.shards_transacoes # 5k registros/s ⇒ ≥5 shards (1k/s cada) + folga; resharding antes de picos
  retention_period = 168                   # 7 dias de replay
  encryption_type  = var.kms_key_arn == null ? "NONE" : "KMS"
  kms_key_id       = var.kms_key_arn

  stream_mode_details {
    stream_mode = "PROVISIONED"
  }

  shard_level_metrics = ["IncomingRecords", "IteratorAgeMilliseconds", "WriteProvisionedThroughputExceeded"]
}

# ---- EventBridge: integração entre domínios -------------------------------------------------------------
resource "aws_cloudwatch_event_bus" "financas" {
  name = "${var.prefixo}financas"
}

resource "aws_cloudwatch_event_archive" "financas" {
  name             = "${var.prefixo}financas-arquivo"
  event_source_arn = aws_cloudwatch_event_bus.financas.arn
  retention_days   = 90 # replay gerenciado após incidente/bug em consumidor
}

resource "aws_cloudwatch_event_rule" "limite_concedido" {
  name           = "${var.prefixo}limite-concedido"
  event_bus_name = aws_cloudwatch_event_bus.financas.name
  event_pattern = jsonencode({
    source        = ["itau.financas.corebanking-acl"]
    "detail-type" = ["br.com.itau.financas.corebanking.limite-concedido.v1"]
  })
}

resource "aws_cloudwatch_event_target" "limite_concedido" {
  rule           = aws_cloudwatch_event_rule.limite_concedido.name
  event_bus_name = aws_cloudwatch_event_bus.financas.name
  arn            = aws_sqs_queue.fila["limites-concedidos"].arn

  retry_policy {
    maximum_event_age_in_seconds = 3600
    maximum_retry_attempts       = 20
  }

  dead_letter_config {
    arn = aws_sqs_queue.dlq_eventbridge.arn
  }
}

resource "aws_cloudwatch_event_rule" "trilha_dominio" {
  name           = "${var.prefixo}trilha-dominio"
  event_bus_name = aws_cloudwatch_event_bus.financas.name
  event_pattern  = jsonencode({ source = [{ prefix = "itau.financas." }] })
}

resource "aws_cloudwatch_event_target" "trilha_dominio" {
  count          = var.habilitar_firehose ? 1 : 0
  rule           = aws_cloudwatch_event_rule.trilha_dominio.name
  event_bus_name = aws_cloudwatch_event_bus.financas.name
  arn            = aws_kinesis_firehose_delivery_stream.dominio[0].arn
  role_arn       = aws_iam_role.eventbridge_firehose[0].arn

  dead_letter_config {
    arn = aws_sqs_queue.dlq_eventbridge.arn
  }
}

# ---- SQS: filas de trabalho com DLQ (desacoplamento + redrive) ------------------------------------------
resource "aws_sqs_queue" "dlq" {
  for_each                  = local.filas
  name                      = "${var.prefixo}${each.key}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "fila" {
  for_each                   = local.filas
  name                       = "${var.prefixo}${each.key}"
  visibility_timeout_seconds = each.value.visibilidade
  delay_seconds              = each.value.atraso
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.recebimentos_max
  })
}

resource "aws_sqs_queue" "dlq_eventbridge" {
  name                      = "${var.prefixo}eventbridge-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

# Quarentena da contabilização (C06): o serviço reprocessa a cada minuto; cada falha devolve a mensagem após
# 1 h (visibility timeout); após 24 recebimentos (~1 dia) ela vai para a DLQ, que tem alarme e tratamento manual.
resource "aws_sqs_queue" "quarentena_contabilizacao_dlq" {
  name                      = "${var.prefixo}contabilizacao-quarentena-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "quarentena_contabilizacao" {
  name                       = "${var.prefixo}contabilizacao-quarentena"
  message_retention_seconds  = 1209600
  visibility_timeout_seconds = 3600
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.quarentena_contabilizacao_dlq.arn
    maxReceiveCount     = 24
  })
}

resource "aws_sqs_queue" "falhas_relay" {
  name                      = "${var.prefixo}outbox-relay-falhas"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

data "aws_iam_policy_document" "eventbridge_para_sqs" {
  for_each = {
    limites-concedidos = aws_sqs_queue.fila["limites-concedidos"].arn
    eventbridge-dlq    = aws_sqs_queue.dlq_eventbridge.arn
  }

  statement {
    actions   = ["sqs:SendMessage"]
    resources = [each.value]
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:events:${data.aws_region.atual.name}:${data.aws_caller_identity.atual.account_id}:rule/${aws_cloudwatch_event_bus.financas.name}/*"]
    }
  }
}

resource "aws_sqs_queue_policy" "eventbridge" {
  for_each  = data.aws_iam_policy_document.eventbridge_para_sqs
  queue_url = each.key == "eventbridge-dlq" ? aws_sqs_queue.dlq_eventbridge.id : aws_sqs_queue.fila[each.key].id
  policy    = each.value.json
}

# ---- Trilha de auditoria imutável (BIAN Archive Services; PDF: auditoria e rastreabilidade) -------------
resource "aws_s3_bucket" "trilha" {
  count               = var.habilitar_firehose ? 1 : 0
  bucket              = "${var.prefixo}trilha-financas-${data.aws_caller_identity.atual.account_id}"
  object_lock_enabled = true
  force_destroy       = false
}

resource "aws_s3_bucket_versioning" "trilha" {
  count  = var.habilitar_firehose ? 1 : 0
  bucket = aws_s3_bucket.trilha[0].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_object_lock_configuration" "trilha" {
  count  = var.habilitar_firehose ? 1 : 0
  bucket = aws_s3_bucket.trilha[0].id
  rule {
    default_retention {
      mode = "COMPLIANCE" # nem a conta root apaga antes do prazo (WORM)
      days = var.retencao_auditoria_dias
    }
  }
}

resource "aws_s3_bucket_public_access_block" "trilha" {
  count                   = var.habilitar_firehose ? 1 : 0
  bucket                  = aws_s3_bucket.trilha[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "trilha" {
  count  = var.habilitar_firehose ? 1 : 0
  bucket = aws_s3_bucket.trilha[0].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn == null ? "AES256" : "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = var.kms_key_arn != null
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "trilha" {
  count  = var.habilitar_firehose ? 1 : 0
  bucket = aws_s3_bucket.trilha[0].id
  rule {
    id     = "frio-apos-90-dias"
    status = "Enabled"
    filter {}
    transition {
      days          = 90
      storage_class = "GLACIER_IR" # Custo/Sustentabilidade: dado frio em classe barata
    }
  }
}

resource "aws_iam_role" "firehose" {
  count = var.habilitar_firehose ? 1 : 0
  name  = "${var.prefixo}firehose-trilha"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "firehose.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "firehose" {
  count = var.habilitar_firehose ? 1 : 0
  role  = aws_iam_role.firehose[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["kinesis:DescribeStream", "kinesis:GetShardIterator", "kinesis:GetRecords", "kinesis:ListShards"]
        Resource = aws_kinesis_stream.transacoes.arn
      },
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:AbortMultipartUpload", "s3:GetBucketLocation", "s3:ListBucket"]
        Resource = [aws_s3_bucket.trilha[0].arn, "${aws_s3_bucket.trilha[0].arn}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource = var.kms_key_arn == null ? "*" : var.kms_key_arn
      }
    ]
  })
}

resource "aws_kinesis_firehose_delivery_stream" "transacoes" {
  count       = var.habilitar_firehose ? 1 : 0
  name        = "${var.prefixo}trilha-transacoes"
  destination = "extended_s3"

  kinesis_source_configuration {
    kinesis_stream_arn = aws_kinesis_stream.transacoes.arn
    role_arn           = aws_iam_role.firehose[0].arn
  }

  extended_s3_configuration {
    role_arn            = aws_iam_role.firehose[0].arn
    bucket_arn          = aws_s3_bucket.trilha[0].arn
    prefix              = "transacoes/ano=!{timestamp:yyyy}/mes=!{timestamp:MM}/dia=!{timestamp:dd}/"
    error_output_prefix = "erros/transacoes/!{firehose:error-output-type}/"
    buffering_size      = 128
    buffering_interval  = 300
    compression_format  = "GZIP"

    # Um evento por linha (JSON Lines): sem delimitador o Firehose concatena os registros ("}{"), e nem o Athena
    # nem a data platform conseguem ler a trilha (corrigido junto com a conciliação, ADR 034).
    processing_configuration {
      enabled = true
      processors {
        type = "AppendDelimiterToRecord"
      }
    }
  }
}

resource "aws_kinesis_firehose_delivery_stream" "dominio" {
  count       = var.habilitar_firehose ? 1 : 0
  name        = "${var.prefixo}trilha-dominio"
  destination = "extended_s3"

  extended_s3_configuration {
    role_arn            = aws_iam_role.firehose[0].arn
    bucket_arn          = aws_s3_bucket.trilha[0].arn
    prefix              = "dominio/ano=!{timestamp:yyyy}/mes=!{timestamp:MM}/dia=!{timestamp:dd}/"
    error_output_prefix = "erros/dominio/!{firehose:error-output-type}/"
    buffering_size      = 64
    buffering_interval  = 300
    compression_format  = "GZIP"

    # Um evento por linha (JSON Lines): sem delimitador o Firehose concatena os registros ("}{"), e nem o Athena
    # nem a data platform conseguem ler a trilha (corrigido junto com a conciliação, ADR 034).
    processing_configuration {
      enabled = true
      processors {
        type = "AppendDelimiterToRecord"
      }
    }
  }
}

resource "aws_iam_role" "eventbridge_firehose" {
  count = var.habilitar_firehose ? 1 : 0
  name  = "${var.prefixo}eventbridge-para-firehose"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "events.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "eventbridge_firehose" {
  count = var.habilitar_firehose ? 1 : 0
  role  = aws_iam_role.eventbridge_firehose[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["firehose:PutRecord", "firehose:PutRecordBatch"]
      Resource = aws_kinesis_firehose_delivery_stream.dominio[0].arn
    }]
  })
}

# ---- Schema registry (evolução retrocompatível validada; PDF §2.4) -------------------------------------
resource "aws_glue_registry" "financas" {
  count         = var.habilitar_schema_registry ? 1 : 0
  registry_name = "${var.prefixo}financas"
  description   = "Contratos CloudEvents v1 da plataforma de limites e autorização"
}

resource "aws_glue_schema" "evento" {
  for_each          = var.habilitar_schema_registry ? fileset(var.diretorio_schemas, "*.json") : toset([])
  schema_name       = trimsuffix(each.value, ".json")
  registry_arn      = aws_glue_registry.financas[0].arn
  data_format       = "JSON"
  compatibility     = "FULL" # consumidores antigos leem eventos novos e vice-versa: só campos opcionais entram/saem
  schema_definition = file("${var.diretorio_schemas}/${each.value}")
}

# ---- Expiração de reservas (BIAN AmountBlock com data de expiração; ADR 035) ----------------------------
# Um agendamento único por reserva (criado pela Lambda agendador-expiracao) entrega o id na fila reservas-a-expirar.
# Dead-letter do PRÓPRIO Scheduler: falha em entregar o alvo, antes da mensagem entrar na SQS principal.
# Distinta da DLQ do consumidor reservas-a-expirar, que cobre falhas APÓS entrega.
resource "aws_sqs_queue" "scheduler_expiracao_dlq" {
  count                     = var.habilitar_expiracao_agendada ? 1 : 0
  name                      = "${var.prefixo}scheduler-expiracao-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_scheduler_schedule_group" "reservas" {
  count = var.habilitar_expiracao_agendada ? 1 : 0
  name  = "${var.prefixo}reservas"
}

resource "aws_iam_role" "agendador_reservas" {
  count = var.habilitar_expiracao_agendada ? 1 : 0
  name  = "${var.prefixo}scheduler-reservas"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "scheduler.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "agendador_reservas" {
  count = var.habilitar_expiracao_agendada ? 1 : 0
  role  = aws_iam_role.agendador_reservas[0].id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["sqs:SendMessage"], Resource = [
      aws_sqs_queue.fila["reservas-a-expirar"].arn,
      aws_sqs_queue.scheduler_expiracao_dlq[0].arn
    ] }]
  })
}
