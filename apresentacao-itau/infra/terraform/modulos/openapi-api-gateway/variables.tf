variable "contrato_openapi" {
  type        = string
  description = "Caminho do contrato OpenAPI 3.0 (fonte da verdade), ex.: contracts/openapi/autorizacoes-v1.yaml."
}

variable "schema_request" {
  type        = string
  description = "Caminho do JSON Schema (draft-04) do corpo da requisição, referenciado pelo contrato."
}

variable "ref_schema_request" {
  type        = string
  description = "Valor do $ref usado no contrato para o schema do corpo."
  default     = "./schemas/autorizacao-request.json"
}

variable "nome_modelo_request" {
  type    = string
  default = "AutorizacaoRequest"
}

variable "esquema_oauth2_do_contrato" {
  type        = string
  description = "Nome do security scheme OAuth2 no contrato; seus escopos viram os escopos do Cognito authorizer."
  default     = "oauth2"
}

variable "uri_backend" {
  type        = string
  description = "Base da integração, ex.: http://<dns do NLB>. O caminho de cada rota é acrescentado."
}

variable "vpc_link_id" {
  type = string
}

variable "cognito_user_pool_arn" {
  type = string
}

variable "timeout_ms" {
  type    = number
  default = 3000
}

variable "nome_validador" {
  type    = string
  default = "corpo-e-parametros"
}

variable "cabecalhos_do_contexto" {
  type        = map(string)
  description = "Cabeçalhos injetados pelo API Gateway a partir do contexto (ex.: X-Client-Id do token)."
  default     = { "X-Client-Id" = "context.authorizer.claims.client_id" }
}

variable "respostas_gateway" {
  type        = any
  description = "Gateway responses (x-amazon-apigateway-gateway-responses), ex.: BAD_REQUEST_BODY → 422."
  default     = {}
}
