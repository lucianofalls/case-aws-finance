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
