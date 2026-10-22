# Conciliação diária — BIAN Account Reconciliation (ADR 034; auditoria C01, C03 e C06).
#
#   EventBridge Scheduler (diário) → Step Functions "conciliacao"
#     1. Lambda preparar-janela: janela (D-1 ou manual ≤ 24 h), prefixos e consultas
#     2. em paralelo: exportação incremental de autorizacoes e limites (DynamoDB → S3, sem consumir capacidade)
#                     + RDS PostgreSQL (Lambda JDBC na VPC) exporta os evento_id contabilizados (aws_s3.query_export_to_s3)
#     3. Athena: fatos não publicados / não contabilizados → Lambda republicador → Kinesis
#                operações sem decisão                      → Lambda agendar-orfas → fila de verificação
#
# As fontes comparadas não dependem do transporte de eventos: é a garantia final quando o DynamoDB Streams (24 h),
# o relay ou um consumidor falham. A mesma máquina de estados, com janela manual, é o runbook de outbox parado.

data "aws_caller_identity" "atual" {}
data "aws_region" "atual" {}

locals {
  banco_glue = replace("${var.prefixo}financas_conciliacao", "-", "_")
  bucket     = "${var.prefixo}conciliacao-financas-${data.aws_caller_identity.atual.account_id}"
  serde_json = "org.openx.data.jsonserde.JsonSerDe"
}

# ---- Bucket da conciliação (exportações, razão, resultados do Athena) -----------------------------------
resource "aws_s3_bucket" "conciliacao" {
  bucket = local.bucket
}

resource "aws_s3_bucket_public_access_block" "conciliacao" {
  bucket                  = aws_s3_bucket.conciliacao.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "conciliacao" {
  bucket = aws_s3_bucket.conciliacao.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "conciliacao" {
  bucket = aws_s3_bucket.conciliacao.id
  rule {
    id     = "resultados-athena"
    status = "Enabled"
    filter {
      prefix = "athena/"
    }
    expiration {
      days = 30
    }
  }
  rule {
    id     = "evidencias-da-conciliacao"
    status = "Enabled"
    filter {}
    transition {
      days          = 90
      storage_class = "GLACIER_IR"
    }
    expiration {
      days = 400
    }
  }
}

# ---- Catálogo (Glue) com projeção de partições: sem crawler e sem MSCK REPAIR ---------------------------
resource "aws_glue_catalog_database" "conciliacao" {
  name = local.banco_glue
}

resource "aws_glue_catalog_table" "autorizacoes" {
  name          = "autorizacoes_exportadas"
  database_name = aws_glue_catalog_database.conciliacao.name
  table_type    = "EXTERNAL_TABLE"
  parameters = {
    "classification"            = "json"
    "projection.enabled"        = "true"
    "projection.dt.type"        = "date"
    "projection.dt.format"      = "yyyy-MM-dd"
    "projection.dt.range"       = "2026-01-01,NOW"
    "storage.location.template" = "s3://${local.bucket}/exports/autorizacoes/dt=$${dt}/"
  }
  partition_keys {
    name = "dt"
    type = "string"
  }
  storage_descriptor {
    location      = "s3://${local.bucket}/exports/autorizacoes/"
    input_format  = "org.apache.hadoop.mapred.TextInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"
    ser_de_info {
      serialization_library = local.serde_json
      # Formato da exportação incremental: {"Metadata":…, "Keys":…, "NewImage":…}; manifestos não são JSON Lines.
      parameters = { "ignore.malformed.json" = "true", "mapping.chaves" = "keys", "mapping.nova_imagem" = "newimage" }
    }
    columns {
      name = "chaves"
      type = "struct<id_autorizacao:struct<s:string>>"
    }
    columns {
      name = "nova_imagem"
      type = "struct<evento_id:struct<s:string>,evento_tipo:struct<s:string>,id_autorizacao:struct<s:string>>"
    }
  }
}

resource "aws_glue_catalog_table" "limites" {
  name          = "limites_exportados"
  database_name = aws_glue_catalog_database.conciliacao.name
  table_type    = "EXTERNAL_TABLE"
  parameters = {
    "classification"            = "json"
    "projection.enabled"        = "true"
    "projection.dt.type"        = "date"
    "projection.dt.format"      = "yyyy-MM-dd"
    "projection.dt.range"       = "2026-01-01,NOW"
    "storage.location.template" = "s3://${local.bucket}/exports/limites/dt=$${dt}/"
  }
  partition_keys {
    name = "dt"
    type = "string"
  }
  storage_descriptor {
    location      = "s3://${local.bucket}/exports/limites/"
    input_format  = "org.apache.hadoop.mapred.TextInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"
    ser_de_info {
      serialization_library = local.serde_json
      parameters            = { "ignore.malformed.json" = "true", "mapping.chaves" = "keys", "mapping.nova_imagem" = "newimage" }
    }
    columns {
      name = "chaves"
      type = "struct<id_contrato:struct<s:string>,sk:struct<s:string>>"
    }
    columns {
      name = "nova_imagem"
      type = "struct<estado:struct<s:string>>"
    }
  }
}

resource "aws_glue_catalog_table" "trilha" {
  name          = "trilha_transacoes"
  database_name = aws_glue_catalog_database.conciliacao.name
  table_type    = "EXTERNAL_TABLE"
  parameters = {
    "classification"            = "json"
    "projection.enabled"        = "true"
    "projection.ano.type"       = "integer"
    "projection.ano.range"      = "2026,2100"
    "projection.mes.type"       = "integer"
    "projection.mes.range"      = "1,12"
    "projection.mes.digits"     = "2"
    "projection.dia.type"       = "integer"
    "projection.dia.range"      = "1,31"
    "projection.dia.digits"     = "2"
    "storage.location.template" = "s3://${var.bucket_trilha.nome}/transacoes/ano=$${ano}/mes=$${mes}/dia=$${dia}/"
  }
  partition_keys {
    name = "ano"
    type = "string"
  }
  partition_keys {
    name = "mes"
    type = "string"
  }
  partition_keys {
    name = "dia"
    type = "string"
  }
  storage_descriptor {
    location      = "s3://${var.bucket_trilha.nome}/transacoes/"
    input_format  = "org.apache.hadoop.mapred.TextInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"
    ser_de_info {
      serialization_library = local.serde_json
      parameters            = { "ignore.malformed.json" = "true" }
    }
    columns {
      name = "id"
      type = "string"
    }
    columns {
      name = "type"
      type = "string"
    }
    columns {
      name = "time"
      type = "string"
    }
    columns {
      name = "data"
      type = "struct<id_autorizacao:string>"
    }
  }
}

resource "aws_glue_catalog_table" "razao" {
  name          = "razao_processado"
  database_name = aws_glue_catalog_database.conciliacao.name
  table_type    = "EXTERNAL_TABLE"
  parameters = {
    "classification"            = "csv"
    "projection.enabled"        = "true"
    "projection.dt.type"        = "date"
    "projection.dt.format"      = "yyyy-MM-dd"
    "projection.dt.range"       = "2026-01-01,NOW"
    "storage.location.template" = "s3://${local.bucket}/razao/dt=$${dt}/"
  }
  partition_keys {
    name = "dt"
    type = "string"
  }
  storage_descriptor {
    location      = "s3://${local.bucket}/razao/"
    input_format  = "org.apache.hadoop.mapred.TextInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"
    ser_de_info {
      serialization_library = "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe"
      parameters            = { "field.delim" = "," }
    }
    columns {
      name = "evento_id"
      type = "string"
    }
  }
}

resource "aws_athena_workgroup" "conciliacao" {
  name          = "${var.prefixo}conciliacao"
  force_destroy = true
  configuration {
    enforce_workgroup_configuration    = true
    publish_cloudwatch_metrics_enabled = true
    bytes_scanned_cutoff_per_query     = 1099511627776 # 1 TB: trava contra consulta errada varrendo tudo
    result_configuration {
      output_location = "s3://${local.bucket}/athena/"
      encryption_configuration {
        encryption_option = "SSE_KMS"
        kms_key_arn       = var.kms_key_arn
      }
    }
  }
}

# ---- RDS PostgreSQL exporta o razão para o S3 (aws_s3.query_export_to_s3) ---------------------------------------
resource "aws_iam_role" "aurora_exportacao" {
  name = "${var.prefixo}aurora-exportacao-conciliacao"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "rds.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "aurora_exportacao" {
  role = aws_iam_role.aurora_exportacao.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:PutObject", "s3:AbortMultipartUpload"], Resource = "${aws_s3_bucket.conciliacao.arn}/razao/*" },
      { Effect = "Allow", Action = ["kms:GenerateDataKey", "kms:Decrypt"], Resource = var.kms_key_arn },
    ]
  })
}

resource "aws_db_instance_role_association" "exportacao" {
  db_instance_identifier = var.rds_postgres.identificador
  feature_name          = "s3Export"
  role_arn              = aws_iam_role.aurora_exportacao.arn
}

# ---- Máquina de estados -------------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "conciliacao" {
  name              = "/aws/states/${var.prefixo}conciliacao"
  retention_in_days = 400
}

resource "aws_iam_role" "maquina" {
  name = "${var.prefixo}conciliacao-step-functions"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "states.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "maquina" {
  role = aws_iam_role.maquina.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["lambda:InvokeFunction"], Resource = [var.funcoes.preparar_janela, var.funcoes.republicador, var.funcoes.agendar_orfas, var.funcoes.exportar_razao] },
      { Effect = "Allow", Action = ["dynamodb:ExportTableToPointInTime"], Resource = [var.tabela_autorizacoes.arn, var.tabela_limites.arn] },
      { Effect = "Allow", Action = ["dynamodb:DescribeExport"], Resource = ["${var.tabela_autorizacoes.arn}/export/*", "${var.tabela_limites.arn}/export/*"] },
      { Effect = "Allow", Action = ["s3:PutObject", "s3:AbortMultipartUpload", "s3:PutObjectAcl", "s3:GetObject"], Resource = "${aws_s3_bucket.conciliacao.arn}/*" },
      { Effect = "Allow", Action = ["s3:GetObject"], Resource = "${var.bucket_trilha.arn}/transacoes/*" },
      { Effect = "Allow", Action = ["s3:ListBucket", "s3:GetBucketLocation"], Resource = [aws_s3_bucket.conciliacao.arn, var.bucket_trilha.arn] },
      { Effect = "Allow", Action = ["athena:StartQueryExecution", "athena:GetQueryExecution", "athena:StopQueryExecution", "athena:GetQueryResults"], Resource = aws_athena_workgroup.conciliacao.arn },
      {
        Effect = "Allow", Action = ["glue:GetDatabase", "glue:GetTable", "glue:GetPartition", "glue:GetPartitions"],
        Resource = [
          "arn:aws:glue:${data.aws_region.atual.name}:${data.aws_caller_identity.atual.account_id}:catalog",
          "arn:aws:glue:${data.aws_region.atual.name}:${data.aws_caller_identity.atual.account_id}:database/${local.banco_glue}",
          "arn:aws:glue:${data.aws_region.atual.name}:${data.aws_caller_identity.atual.account_id}:table/${local.banco_glue}/*",
        ]
      },
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = var.kms_key_arn },
      {
        Effect   = "Allow",
        Action   = ["logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery", "logs:DeleteLogDelivery", "logs:ListLogDeliveries", "logs:PutResourcePolicy", "logs:DescribeResourcePolicies", "logs:DescribeLogGroups"],
        Resource = "*"
      },
    ]
  })
}

resource "aws_sfn_state_machine" "conciliacao" {
  name     = "${var.prefixo}conciliacao"
  role_arn = aws_iam_role.maquina.arn
  definition = templatefile("${path.module}/maquina-de-estados.asl.json", {
    preparar_janela_arn     = var.funcoes.preparar_janela
    republicador_arn        = var.funcoes.republicador
    agendar_orfas_arn       = var.funcoes.agendar_orfas
    tabela_autorizacoes_arn = var.tabela_autorizacoes.arn
    tabela_limites_arn      = var.tabela_limites.arn
    bucket                  = aws_s3_bucket.conciliacao.bucket
    kms_key_arn             = var.kms_key_arn
    exportar_razao_arn      = var.funcoes.exportar_razao
    workgroup               = aws_athena_workgroup.conciliacao.name
  })

  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.conciliacao.arn}:*"
    include_execution_data = false
    level                  = "ERROR"
  }

  tracing_configuration {
    enabled = true
  }
}

# ---- Agendamento diário -------------------------------------------------------------------------------
resource "aws_iam_role" "agendador" {
  name = "${var.prefixo}conciliacao-scheduler"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "scheduler.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "agendador" {
  role = aws_iam_role.agendador.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["states:StartExecution"], Resource = aws_sfn_state_machine.conciliacao.arn }]
  })
}

resource "aws_scheduler_schedule" "diaria" {
  name                         = "${var.prefixo}conciliacao-diaria"
  schedule_expression          = var.agendamento
  schedule_expression_timezone = "UTC"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_sfn_state_machine.conciliacao.arn
    role_arn = aws_iam_role.agendador.arn
    input    = jsonencode({})
  }
}

# ---- Alarmes ------------------------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "execucao_falhou" {
  alarm_name          = "${var.prefixo}conciliacao-falhou"
  alarm_description   = "A conciliação diária não terminou: rodar de novo (mesma janela) após corrigir a causa"
  namespace           = "AWS/States"
  metric_name         = "ExecutionsFailed"
  dimensions          = { StateMachineArn = aws_sfn_state_machine.conciliacao.arn }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.topico_alarmes_arn]
}

resource "aws_cloudwatch_metric_alarm" "divergencias" {
  for_each = {
    FatosRepublicados   = "Fatos sem publicação ou sem contabilização foram republicados: investigar a causa (relay, consumidor)"
    OperacoesSemDecisao = "Operações no Limites sem decisão gravada foram enviadas à verificação: investigar a causa"
  }
  alarm_name          = "${var.prefixo}conciliacao-${lower(each.key)}"
  alarm_description   = each.value
  namespace           = "Financas/Conciliacao"
  metric_name         = each.key
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.topico_alarmes_arn]
}
