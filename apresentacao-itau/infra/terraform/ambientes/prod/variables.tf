variable "regiao" {
  type    = string
  default = "sa-east-1"
}

variable "centro_de_custo" {
  type    = string
  default = "POD99-FINANCAS"
}

variable "versao_imagem" {
  type        = string
  description = "Tag imutável das imagens no ECR (SHA do commit)."
}

variable "diretorio_artefatos" {
  type        = string
  description = "Diretório com os JARs das Lambdas (ex.: ../../../../artefatos)."
}

variable "modo_cobranca_dynamodb" {
  type        = string
  description = "PROVISIONED em carga estável (recomendado após o baseline); PAY_PER_REQUEST no go-live."
  default     = "PROVISIONED"
}

variable "shards_transacoes" {
  type    = number
  default = 8
}


variable "habilitar_eventbridge_transacoes" {
  type        = bool
  default     = false
  description = "Ativa a ponte Kinesis -> EventBridge para TransacaoAutorizada, após review de quotas e custo. ADR-040."
}

variable "versao_kubernetes" {
  type=string
  description="EKS Auto Mode cluster version; conferir suporte sa-east-1 antes do plan"
  default="1.34"
}

# Segredo da role PostgreSQL de exportação: NUNCA usar o segredo master do RDS.
# O DBA provisiona uma credencial de consulta/exportação dedicada e a guarda no Secrets Manager.
variable "segredo_exportacao_rds_arn" {
  type        = string
  sensitive   = true
  description = "ARN de um segredo Secrets Manager contendo username/password da role de exportacao no RDS PostgreSQL."
  validation {
    condition     = can(regex("^arn:aws:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:", var.segredo_exportacao_rds_arn))
    error_message = "Informe o ARN de um segredo dedicado a exportacao PostgreSQL, nunca as credenciais master."
  }
}
