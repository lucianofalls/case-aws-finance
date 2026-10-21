output "tabela_autorizacoes" {
  value = {
    nome       = aws_dynamodb_table.autorizacoes.name
    arn        = aws_dynamodb_table.autorizacoes.arn
    stream_arn = aws_dynamodb_table.autorizacoes.stream_arn
  }
}

output "tabela_limites" {
  value = {
    nome       = aws_dynamodb_table.limites.name
    arn        = aws_dynamodb_table.limites.arn
    stream_arn = aws_dynamodb_table.limites.stream_arn
  }
}

output "tabela_leases_kcl" {
  value = {
    nome = aws_dynamodb_table.leases_kcl.name
    arn  = aws_dynamodb_table.leases_kcl.arn
  }
}

output "aurora" {
  value = var.habilitar_aurora ? {
    endpoint          = aws_rds_cluster.contabilizacao[0].endpoint
    arn               = aws_rds_cluster.contabilizacao[0].arn
    identificador     = aws_rds_cluster.contabilizacao[0].cluster_identifier
    segredo_senha_arn = aws_rds_cluster.contabilizacao[0].master_user_secret[0].secret_arn
  } : null
}
