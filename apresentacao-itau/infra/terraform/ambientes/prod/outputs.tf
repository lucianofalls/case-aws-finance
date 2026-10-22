output "url_api" {
  value = module.api.url_invocacao
}

output "token_endpoint" {
  value = module.api.token_endpoint
}

output "repositorios_ecr" {
  value = module.eks.repositorios
}

output "topico_alarmes" {
  value = module.observabilidade.topico_alarmes_arn
}

output "maquina_conciliacao_arn" {
  description = "Conciliação diária; janela manual no runbook docs/runbooks/outbox-parado.md."
  value       = module.conciliacao.maquina_de_estados_arn
}

output "eks_cluster" { value = module.eks.cluster_name }
output "eks_nlb" { value = module.eks.nlb }
output "eks_target_groups" { value = module.eks.target_groups }
output "rds_postgres_host" { value = module.dados.rds_postgres.endereco }
output "rds_postgres_segredo_arn" {
  value     = module.dados.rds_postgres.segredo_senha_arn
  sensitive = true
}
