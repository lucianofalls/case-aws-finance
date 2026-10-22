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

output "rds_postgres" {
  value = var.habilitar_rds_postgres ? {
    endereco = aws_db_instance.contabilizacao[0].address
    arn = aws_db_instance.contabilizacao[0].arn
    identificador = aws_db_instance.contabilizacao[0].identifier
    segredo_senha_arn = aws_db_instance.contabilizacao[0].master_user_secret[0].secret_arn
    sg_id = aws_security_group.postgres[0].id
  } : null
}

output "tabela_permissoes_contratos" {
  value = { nome=aws_dynamodb_table.permissoes_contratos.name, arn=aws_dynamodb_table.permissoes_contratos.arn }
}
