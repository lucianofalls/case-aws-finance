output "url_invocacao" {
  value = aws_api_gateway_stage.publicado.invoke_url
}

output "stage_api" {
  value = aws_api_gateway_stage.publicado.stage_name
}

output "nome_api" {
  value = aws_api_gateway_rest_api.financas.name
}

output "token_endpoint" {
  value = "https://${aws_cognito_user_pool_domain.clientes.domain}.auth.${data.aws_region.atual.name}.amazoncognito.com/oauth2/token"
}

output "client_id_exemplo" {
  value = aws_cognito_user_pool_client.canal_exemplo.id
}
