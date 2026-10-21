output "bucket" {
  value = { nome = aws_s3_bucket.conciliacao.bucket, arn = aws_s3_bucket.conciliacao.arn }
}

output "banco_glue" {
  value = aws_glue_catalog_database.conciliacao.name
}

output "maquina_de_estados_arn" {
  description = "Para a janela manual (runbook): aws stepfunctions start-execution --input '{\"desde\":…,\"ate\":…}'."
  value       = aws_sfn_state_machine.conciliacao.arn
}
