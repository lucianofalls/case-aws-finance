variable "prefixo" {
  type    = string
  default = ""
}

variable "modo_cobranca" {
  type        = string
  description = "PROVISIONED (carga estável, mais barato) ou PAY_PER_REQUEST (ambientes novos/imprevisíveis)."
  default     = "PROVISIONED"

  validation {
    condition     = contains(["PROVISIONED", "PAY_PER_REQUEST"], var.modo_cobranca)
    error_message = "modo_cobranca deve ser PROVISIONED ou PAY_PER_REQUEST."
  }
}

variable "capacidade" {
  description = "Limites do auto scaling por tabela (WCU/RCU). Dimensionado em docs/custos.md."
  type = map(object({
    escrita_min = number
    escrita_max = number
    leitura_min = number
    leitura_max = number
  }))
  default = {
    autorizacoes = { escrita_min = 2000, escrita_max = 8000, leitura_min = 2000, leitura_max = 8000 }
    limites      = { escrita_min = 8000, escrita_max = 30000, leitura_min = 4000, leitura_max = 15000 }
  }
}

variable "kms_key_arn" {
  type        = string
  description = "CMK do domínio financeiro (null = chave gerenciada pela AWS)."
  default     = null
}

variable "protecao_contra_exclusao" {
  type    = bool
  default = true
}

variable "nome_aplicacao_kcl" {
  type    = string
  default = "contabilizacao-service"
}

variable "habilitar_rds_postgres" {
  type    = bool
  default = true
}
variable "versao_postgres" {
  type    = string
  default = "16.8"
}
variable "classe_instancia_postgres" {
  type    = string
  default = "db.r7g.2xlarge"
}
variable "armazenamento_gb" {
  type    = number
  default = 500
}
variable "armazenamento_max_gb" {
  type    = number
  default = 2000
}
variable "iops_postgres" {
  type    = number
  default = 12000
}
variable "throughput_postgres" {
  type    = number
  default = 500
}
variable "origens_rds_security_groups" {
  type    = list(string)
  default = []
}

variable "vpc_id" {
  type    = string
  default = null
}

variable "cidr_vpc" {
  type    = string
  default = null
}

variable "subnets_privadas" {
  type    = list(string)
  default = []
}
