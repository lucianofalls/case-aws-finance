output "stream_transacoes" {
  value = {
    nome = aws_kinesis_stream.transacoes.name
    arn  = aws_kinesis_stream.transacoes.arn
  }
}

output "barramento" {
  value = {
    nome = aws_cloudwatch_event_bus.financas.name
    arn  = aws_cloudwatch_event_bus.financas.arn
  }
}

output "filas" {
  value = { for nome, fila in aws_sqs_queue.fila : nome => { url = fila.url, arn = fila.arn } }
}

output "dlqs" {
  value = merge(
    { for nome, fila in aws_sqs_queue.dlq : nome => fila.arn },
    {
      eventbridge               = aws_sqs_queue.dlq_eventbridge.arn
      contabilizacao-quarentena = aws_sqs_queue.quarentena_contabilizacao.arn
      outbox-relay-falhas       = aws_sqs_queue.falhas_relay.arn
    }
  )
}

output "dlq_nomes" {
  value = merge(
    { for nome, fila in aws_sqs_queue.dlq : nome => fila.name },
    {
      eventbridge                   = aws_sqs_queue.dlq_eventbridge.name
      contabilizacao-quarentena     = aws_sqs_queue.quarentena_contabilizacao.name
      contabilizacao-quarentena-dlq = aws_sqs_queue.quarentena_contabilizacao_dlq.name
      outbox-relay-falhas           = aws_sqs_queue.falhas_relay.name
    }
  )
}

output "fila_quarentena_contabilizacao" {
  value = { url = aws_sqs_queue.quarentena_contabilizacao.url, arn = aws_sqs_queue.quarentena_contabilizacao.arn }
}

output "fila_falhas_relay_arn" {
  value = aws_sqs_queue.falhas_relay.arn
}

output "bucket_trilha" {
  description = "Trilha WORM dos eventos (Firehose → S3 Object Lock); null quando o Firehose está desligado."
  value       = var.habilitar_firehose ? { nome = aws_s3_bucket.trilha[0].bucket, arn = aws_s3_bucket.trilha[0].arn } : null
}

output "expiracao_de_reservas" {
  description = "Grupo do EventBridge Scheduler, papel que o Scheduler assume e fila de destino (ADR 035)."
  value = {
    grupo     = var.habilitar_expiracao_agendada ? aws_scheduler_schedule_group.reservas[0].name : null
    papel_arn = var.habilitar_expiracao_agendada ? aws_iam_role.agendador_reservas[0].arn : null
    fila_arn  = aws_sqs_queue.fila["reservas-a-expirar"].arn
    fila_url  = aws_sqs_queue.fila["reservas-a-expirar"].url
  }
}
