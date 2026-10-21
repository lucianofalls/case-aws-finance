output "corpo" {
  description = "Definição OpenAPI + extensões AWS, pronta para aws_api_gateway_rest_api.body."
  value       = local.corpo_json
}

output "versao_contrato" {
  description = "info.version do contrato (vai para a descrição do deployment)."
  value       = local.contrato.info.version
}
