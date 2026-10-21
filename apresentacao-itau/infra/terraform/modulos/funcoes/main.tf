# Lambdas (Java 21, ARM64, SnapStart) — "cola" por evento, volume baixo/irregular ou lote barato (ADR 023):
#   relay-autorizacoes  DynamoDB Streams (autorizacoes) → Kinesis      (outbox embutido)
#   relay-limites       DynamoDB Streams (limites, OUT#) → EventBridge (outbox explícito)
#   agendador-verif.    DynamoDB Streams (limites, OPERACAO# novo) → SQS autorizacoes-verificacao (C01, ADR 032)
#   reconciliador       SQS autorizacoes-verificacao (atraso 15 min) → reverte operação órfã no Limites
#   corebanking-acl     SQS corebanking-contratos (legado) → EventBridge LimiteConcedido
#   antifraude-stub     Kinesis transacoes
#   notificacoes-stub   Kinesis transacoes (filtro: só autorizadas/estornos)

data "aws_region" "atual" {}

locals {
  # Lambdas da conciliação diária (ADR 034): só existem quando a conciliação está habilitada (prod).
  nomes_conciliacao = ["preparar-janela", "republicador", "agendar-orfas"]

  todas = {
    relay-autorizacoes = {
      artefato = "outbox-relay"
      handler  = "br.com.itau.financas.relay.Handlers$AutorizacoesParaKinesis::handleRequest"
      memoria  = 1024
      timeout  = 60
      na_vpc   = false
      ambiente = { STREAM_TRANSACOES = var.stream_transacoes.nome }
      politica = [
        { acoes = ["dynamodb:DescribeStream", "dynamodb:GetRecords", "dynamodb:GetShardIterator", "dynamodb:ListStreams"], recursos = [var.tabela_autorizacoes.stream_arn] },
        { acoes = ["kinesis:PutRecords", "kinesis:PutRecord"], recursos = [var.stream_transacoes.arn] },
        { acoes = ["sqs:SendMessage"], recursos = [var.fila_falhas_relay_arn] },
      ]
    }
    relay-limites = {
      artefato = "outbox-relay"
      handler  = "br.com.itau.financas.relay.Handlers$LimitesParaEventBridge::handleRequest"
      memoria  = 512
      timeout  = 60
      na_vpc   = false
      ambiente = { BARRAMENTO_EVENTOS = var.barramento.nome }
      politica = [
        { acoes = ["dynamodb:DescribeStream", "dynamodb:GetRecords", "dynamodb:GetShardIterator", "dynamodb:ListStreams"], recursos = [var.tabela_limites.stream_arn] },
        { acoes = ["events:PutEvents"], recursos = [var.barramento.arn] },
        { acoes = ["sqs:SendMessage"], recursos = [var.fila_falhas_relay_arn] },
      ]
    }
    agendador-verificacao = {
      artefato = "reconciliador"
      handler  = "br.com.itau.financas.reconciliador.AgendadorDeVerificacaoHandler::handleRequest"
      memoria  = 512
      timeout  = 60
      na_vpc   = false
      ambiente = { FILA_VERIFICACAO_URL = var.fila_verificacao_url }
      politica = [
        { acoes = ["dynamodb:DescribeStream", "dynamodb:GetRecords", "dynamodb:GetShardIterator", "dynamodb:ListStreams"], recursos = [var.tabela_limites.stream_arn] },
        { acoes = ["sqs:SendMessage"], recursos = [var.fila_verificacao_arn, var.fila_falhas_relay_arn] },
      ]
    }
    reconciliador = {
      artefato = "reconciliador"
      handler  = "br.com.itau.financas.reconciliador.VerificacaoPosteriorHandler::handleRequest"
      memoria  = 512
      timeout  = 60
      na_vpc   = true # chama a API interna do Limites pelo NLB interno
      ambiente = { TABELA_AUTORIZACOES = var.tabela_autorizacoes.nome, LIMITES_URL = var.url_interna_limites }
      politica = [
        { acoes = ["dynamodb:GetItem"], recursos = [var.tabela_autorizacoes.arn] },
        { acoes = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"], recursos = [var.fila_verificacao_arn] },
      ]
    }
    corebanking-acl = {
      artefato = "corebanking-acl"
      handler  = "br.com.itau.financas.acl.ContratoLegadoHandler::handleRequest"
      memoria  = 512
      timeout  = 30
      na_vpc   = false
      ambiente = { BARRAMENTO_EVENTOS = var.barramento.nome }
      politica = [
        { acoes = ["events:PutEvents"], recursos = [var.barramento.arn] },
        { acoes = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"], recursos = [var.fila_corebanking_arn] },
      ]
    }
    antifraude-stub = {
      artefato = "consumidores-stub"
      handler  = "br.com.itau.financas.stubs.AntifraudeStub::handleRequest"
      memoria  = 512
      timeout  = 30
      na_vpc   = false
      ambiente = {}
      politica = [
        { acoes = ["kinesis:DescribeStream", "kinesis:DescribeStreamSummary", "kinesis:GetRecords", "kinesis:GetShardIterator", "kinesis:ListShards", "kinesis:ListStreams"], recursos = [var.stream_transacoes.arn] },
      ]
    }
    notificacoes-stub = {
      artefato = "consumidores-stub"
      handler  = "br.com.itau.financas.stubs.NotificacoesStub::handleRequest"
      memoria  = 512
      timeout  = 30
      na_vpc   = false
      ambiente = {}
      politica = [
        { acoes = ["kinesis:DescribeStream", "kinesis:DescribeStreamSummary", "kinesis:GetRecords", "kinesis:GetShardIterator", "kinesis:ListShards", "kinesis:ListStreams"], recursos = [var.stream_transacoes.arn] },
      ]
    }
    agendador-expiracao = {
      artefato = "reconciliador"
      handler  = "br.com.itau.financas.reconciliador.expiracao.AgendadorDeExpiracaoHandler::handleRequest"
      memoria  = 512
      timeout  = 60
      na_vpc   = false
      ambiente = {
        GRUPO_AGENDAMENTOS          = try(var.expiracao.grupo, "")
        FILA_RESERVAS_A_EXPIRAR_ARN = try(var.expiracao.fila_arn, "")
        PAPEL_AGENDADOR_ARN         = try(var.expiracao.papel_arn, "")
      }
      politica = [
        { acoes = ["kinesis:DescribeStream", "kinesis:DescribeStreamSummary", "kinesis:GetRecords", "kinesis:GetShardIterator", "kinesis:ListShards", "kinesis:ListStreams"], recursos = [var.stream_transacoes.arn] },
        { acoes = ["scheduler:CreateSchedule"], recursos = ["arn:aws:scheduler:${data.aws_region.atual.name}:*:schedule/${try(var.expiracao.grupo, "")}/*"] },
        { acoes = ["iam:PassRole"], recursos = [try(var.expiracao.papel_arn, "")] },
      ]
    }
    preparar-janela = {
      artefato = "reconciliador"
      handler  = "br.com.itau.financas.reconciliador.conciliacao.PrepararJanelaHandler::handleRequest"
      memoria  = 512
      timeout  = 30
      na_vpc   = false
      ambiente = { BANCO_GLUE = try(var.conciliacao.banco_glue, ""), BUCKET_CONCILIACAO = try(var.conciliacao.bucket_nome, "") }
      politica = []
    }
    republicador = {
      artefato = "outbox-relay"
      handler  = "br.com.itau.financas.relay.autorizacoes.RepublicadorHandler::handleRequest"
      memoria  = 1024
      timeout  = 900
      na_vpc   = false
      ambiente = { TABELA_AUTORIZACOES = var.tabela_autorizacoes.nome, STREAM_TRANSACOES = var.stream_transacoes.nome }
      politica = [
        { acoes = ["s3:GetObject"], recursos = ["${try(var.conciliacao.bucket_arn, "")}/athena/*"] },
        { acoes = ["dynamodb:BatchGetItem"], recursos = [var.tabela_autorizacoes.arn] },
        { acoes = ["kinesis:PutRecords", "kinesis:PutRecord"], recursos = [var.stream_transacoes.arn] },
      ]
    }
    agendar-orfas = {
      artefato = "reconciliador"
      handler  = "br.com.itau.financas.reconciliador.conciliacao.AgendarOrfasHandler::handleRequest"
      memoria  = 512
      timeout  = 300
      na_vpc   = false
      ambiente = { FILA_VERIFICACAO_URL = var.fila_verificacao_url }
      politica = [
        { acoes = ["s3:GetObject"], recursos = ["${try(var.conciliacao.bucket_arn, "")}/athena/*"] },
        { acoes = ["sqs:SendMessage"], recursos = [var.fila_verificacao_arn] },
      ]
    }
  }

  funcoes = {
    for nome, funcao in local.todas : nome => funcao
    if(var.conciliacao != null || !contains(local.nomes_conciliacao, nome)) && (var.expiracao != null || nome != "agendador-expiracao")
  }
}

resource "aws_cloudwatch_log_group" "funcao" {
  for_each          = local.funcoes
  name              = "/aws/lambda/${var.prefixo}${each.key}"
  retention_in_days = 30
}

resource "aws_iam_role" "funcao" {
  for_each = local.funcoes
  name     = "${var.prefixo}${each.key}-lambda"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "funcao" {
  for_each = local.funcoes
  role     = aws_iam_role.funcao[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [for regra in each.value.politica : { Effect = "Allow", Action = regra.acoes, Resource = regra.recursos }],
      [{ Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.funcao[each.key].arn}:*" }],
      var.kms_key_arn == null ? [] : [{ Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = var.kms_key_arn }]
    )
  })
}

resource "aws_iam_role_policy_attachment" "vpc" {
  for_each   = { for nome, f in local.funcoes : nome => f if f.na_vpc }
  role       = aws_iam_role.funcao[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_lambda_function" "funcao" {
  for_each         = local.funcoes
  function_name    = "${var.prefixo}${each.key}"
  role             = aws_iam_role.funcao[each.key].arn
  runtime          = "java21"
  architectures    = ["arm64"]
  handler          = each.value.handler
  filename         = "${var.diretorio_artefatos}/${each.value.artefato}.jar"
  source_code_hash = filebase64sha256("${var.diretorio_artefatos}/${each.value.artefato}.jar")
  memory_size      = each.value.memoria
  timeout          = each.value.timeout
  publish          = true # SnapStart exige versão publicada

  environment {
    variables = merge(each.value.ambiente, var.variaveis_extras)
  }

  dynamic "snap_start" {
    for_each = var.habilitar_snapstart ? [1] : []
    content {
      apply_on = "PublishedVersions"
    }
  }

  dynamic "vpc_config" {
    for_each = each.value.na_vpc && length(var.subnets_privadas) > 0 ? [1] : []
    content {
      subnet_ids         = var.subnets_privadas
      security_group_ids = [var.security_group_lambdas]
    }
  }

  tracing_config {
    mode = "Active"
  }

  depends_on = [aws_cloudwatch_log_group.funcao]
}

resource "aws_lambda_alias" "atual" {
  for_each         = aws_lambda_function.funcao
  name             = "atual"
  function_name    = each.value.function_name
  function_version = each.value.version
}

# ---- Event source mappings ----------------------------------------------------------------------------
resource "aws_lambda_event_source_mapping" "relay_autorizacoes" {
  function_name                      = aws_lambda_alias.atual["relay-autorizacoes"].arn
  event_source_arn                   = var.tabela_autorizacoes.stream_arn
  starting_position                  = "TRIM_HORIZON"
  batch_size                         = 500
  maximum_batching_window_in_seconds = 1
  parallelization_factor             = 10 # vazão: ordem só por item; entre itens do mesmo contrato não há ordem (C02)
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_retry_attempts             = 10000 # outbox não pode perder evento; alarme de IteratorAge
  maximum_record_age_in_seconds      = -1

  filter_criteria {
    filter {
      pattern = jsonencode({ eventName = ["INSERT", "MODIFY"] }) # TTL (REMOVE) não é fato de negócio
    }
  }

  destination_config {
    on_failure {
      destination_arn = var.fila_falhas_relay_arn
    }
  }
}

# C01 / ADR 032: toda operação gravada no Limites agenda a própria verificação. É o 2º leitor do stream de limites
# (o DynamoDB Streams admite 2 leitores simultâneos por shard: relay-limites e este).
resource "aws_lambda_event_source_mapping" "agendador_verificacao" {
  function_name                      = aws_lambda_alias.atual["agendador-verificacao"].arn
  event_source_arn                   = var.tabela_limites.stream_arn
  starting_position                  = "TRIM_HORIZON"
  batch_size                         = 500
  maximum_batching_window_in_seconds = 1
  parallelization_factor             = 10
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_retry_attempts             = 10000 # compensação não pode perder agendamento; alarme de IteratorAge
  maximum_record_age_in_seconds      = -1

  filter_criteria {
    filter {
      pattern = jsonencode({ eventName = ["INSERT"], dynamodb = { Keys = { sk = { S = [{ prefix = "OPERACAO#" }] } } } })
    }
  }

  destination_config {
    on_failure {
      destination_arn = var.fila_falhas_relay_arn
    }
  }
}

resource "aws_lambda_event_source_mapping" "relay_limites" {
  function_name                      = aws_lambda_alias.atual["relay-limites"].arn
  event_source_arn                   = var.tabela_limites.stream_arn
  starting_position                  = "TRIM_HORIZON"
  batch_size                         = 100
  maximum_batching_window_in_seconds = 1
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_retry_attempts             = 10000
  maximum_record_age_in_seconds      = -1

  filter_criteria {
    filter {
      # Só o outbox: cada reserva (milhões/dia) é descartada no filtro, sem invocar nem cobrar a Lambda.
      pattern = jsonencode({ eventName = ["INSERT"], dynamodb = { Keys = { sk = { S = [{ prefix = "OUT#" }] } } } })
    }
  }

  destination_config {
    on_failure {
      destination_arn = var.fila_falhas_relay_arn
    }
  }
}

resource "aws_lambda_event_source_mapping" "reconciliador" {
  function_name           = aws_lambda_alias.atual["reconciliador"].arn
  event_source_arn        = var.fila_verificacao_arn
  batch_size              = 10
  function_response_types = ["ReportBatchItemFailures"]

  scaling_config {
    maximum_concurrency = 5
  }
}

resource "aws_lambda_event_source_mapping" "corebanking_acl" {
  function_name           = aws_lambda_alias.atual["corebanking-acl"].arn
  event_source_arn        = var.fila_corebanking_arn
  batch_size              = 10
  function_response_types = ["ReportBatchItemFailures"]
}

resource "aws_lambda_event_source_mapping" "stubs" {
  for_each = {
    antifraude-stub   = ["br.com.itau.financas.autorizacao.transacao-autorizada.v1", "br.com.itau.financas.autorizacao.transacao-recusada.v1"]
    notificacoes-stub = ["br.com.itau.financas.autorizacao.transacao-autorizada.v1", "br.com.itau.financas.autorizacao.estorno-realizado.v1"]
  }
  function_name                      = aws_lambda_alias.atual[each.key].arn
  event_source_arn                   = var.stream_transacoes.arn
  starting_position                  = "LATEST"
  batch_size                         = 500
  maximum_batching_window_in_seconds = 5
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_retry_attempts             = 5
  bisect_batch_on_function_error     = true

  filter_criteria {
    filter {
      pattern = jsonencode({ data = { type = each.value } })
    }
  }
}

# ADR 035: cada RESERVA aprovada ganha um agendamento único de expiração. O filtro descarta os demais fatos sem
# invocar (nem cobrar) a Lambda.
resource "aws_lambda_event_source_mapping" "agendador_expiracao" {
  count                              = var.expiracao == null ? 0 : 1
  function_name                      = aws_lambda_alias.atual["agendador-expiracao"].arn
  event_source_arn                   = var.stream_transacoes.arn
  starting_position                  = "TRIM_HORIZON"
  batch_size                         = 100
  maximum_batching_window_in_seconds = 1
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_retry_attempts             = 10000 # reserva sem expiração agendada ficaria bloqueada; alarme de IteratorAge
  maximum_record_age_in_seconds      = -1

  filter_criteria {
    filter {
      pattern = jsonencode({ data = {
        type = ["br.com.itau.financas.autorizacao.transacao-autorizada.v1"]
        data = { tipo_operacao = ["RESERVA"] }
      } })
    }
  }

  destination_config {
    on_failure {
      destination_arn = var.fila_falhas_relay_arn
    }
  }
}
