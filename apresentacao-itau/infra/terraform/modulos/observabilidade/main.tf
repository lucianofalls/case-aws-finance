# Alarmes que protegem as garantias da arquitetura (Excelência Operacional + Confiabilidade).
# Traces: ADOT → X-Ray (sidecar nas tasks) e tracing ativo nas Lambdas/API Gateway.

resource "aws_sns_topic" "alarmes" {
  name = "${var.prefixo}financas-alarmes"
}

resource "aws_cloudwatch_metric_alarm" "latencia_p99_api" {
  alarm_name          = "${var.prefixo}api-latencia-p99"
  alarm_description   = "p99 da autorização acima do NFR (100 ms)"
  namespace           = "AWS/ApiGateway"
  metric_name         = "Latency"
  dimensions          = { ApiName = var.nome_api, Stage = var.stage_api }
  extended_statistic  = "p99"
  period              = 60
  evaluation_periods  = 5
  threshold           = 100
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarmes.arn]
}

resource "aws_cloudwatch_metric_alarm" "erros_5xx_api" {
  alarm_name          = "${var.prefixo}api-5xx"
  alarm_description   = "Taxa de 5xx (inclui 503 do circuit breaker) acima de 1%"
  namespace           = "AWS/ApiGateway"
  metric_name         = "5XXError"
  dimensions          = { ApiName = var.nome_api, Stage = var.stage_api }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 3
  threshold           = 0.01
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarmes.arn]
}

resource "aws_cloudwatch_metric_alarm" "throttling_dynamodb" {
  for_each            = toset(var.tabelas_dynamodb)
  alarm_name          = "${var.prefixo}dynamodb-throttling-${each.key}"
  alarm_description   = "Escrita estrangulada: subir capacidade / warm throughput antes do pico"
  namespace           = "AWS/DynamoDB"
  metric_name         = "WriteThrottleEvents"
  dimensions          = { TableName = each.key }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarmes.arn]
}

resource "aws_cloudwatch_metric_alarm" "atraso_outbox" {
  for_each            = toset(var.funcoes_relay)
  alarm_name          = "${var.prefixo}outbox-atrasado-${each.key}"
  alarm_description   = "Eventos do outbox parados no Stream (publicação atrasada para consumidores)"
  namespace           = "AWS/Lambda"
  metric_name         = "IteratorAge"
  dimensions          = { FunctionName = each.key }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 60000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarmes.arn]
}

resource "aws_cloudwatch_metric_alarm" "atraso_kinesis" {
  alarm_name          = "${var.prefixo}kinesis-transacoes-atrasado"
  alarm_description   = "Consumidores do stream de transações atrasados (contabilidade fora do SLA)"
  namespace           = "AWS/Kinesis"
  metric_name         = "GetRecords.IteratorAgeMilliseconds"
  dimensions          = { StreamName = var.stream_transacoes }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 300000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarmes.arn]
}

resource "aws_cloudwatch_metric_alarm" "dlq_com_mensagens" {
  for_each            = var.dlqs
  alarm_name          = "${var.prefixo}dlq-${each.key}"
  alarm_description   = "Mensagem em DLQ/quarentena: investigar e fazer redrive"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = each.value }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarmes.arn]
}
