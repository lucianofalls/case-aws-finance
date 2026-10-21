output "url_api" {
  value = module.api.url_invocacao
}

output "token_endpoint" {
  value = module.api.token_endpoint
}

output "repositorios_ecr" {
  value = module.compute.repositorios_ecr
}

output "topico_alarmes" {
  value = module.observabilidade.topico_alarmes_arn
}

output "maquina_conciliacao_arn" {
  description = "Conciliação diária; janela manual no runbook docs/runbooks/outbox-parado.md."
  value       = module.conciliacao.maquina_de_estados_arn
}
