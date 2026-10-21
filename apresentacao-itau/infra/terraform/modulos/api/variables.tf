variable "prefixo" {
  type    = string
  default = ""
}

variable "nlb_arn" {
  type = string
}

variable "nlb_dns_name" {
  type = string
}

variable "contrato_openapi" {
  type        = string
  description = "Contrato OpenAPI 3.0 da API pública — contracts/openapi/autorizacoes-v1.yaml (fonte da API publicada)."
}

variable "schema_request_autorizacao" {
  type        = string
  description = "JSON Schema (draft-04) do corpo da requisição — contracts/openapi/schemas/autorizacao-request.json."
}

variable "sufixo_dominio_cognito" {
  type        = string
  description = "Sufixo único global do domínio Cognito (ex.: id da conta)."
}

variable "throttling_estagio" {
  description = "Teto do estágio (proteção do backend). Quota da conta em sa-east-1: 10.000 RPS (doc AWS)."
  type = object({
    taxa   = number
    rajada = number
  })
  default = { taxa = 6000, rajada = 3000 }
}

variable "limite_waf_por_ip" {
  type        = number
  description = "Requisições por IP em 5 minutos antes do bloqueio."
  default     = 300000
}

variable "gerenciar_conta_api_gateway" {
  type        = bool
  description = "Cria a role de CloudWatch da conta do API Gateway (única por conta/região). false se a conta já a gerencia."
  default     = true
}
