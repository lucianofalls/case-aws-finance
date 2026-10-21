variable "prefixo" {
  type    = string
  default = ""
}

variable "vpc_id" {
  type = string
}

variable "cidr_vpc" {
  type = string
}

variable "subnets_privadas" {
  type = list(string)
}

variable "versao_imagem" {
  type        = string
  description = "Tag imutável da imagem no ECR (ex.: SHA do commit)."
}

variable "escala" {
  description = "Mínimo/máximo de tasks por serviço. Mínimo ≥ 3 nos síncronos (1 por AZ)."
  type = map(object({
    min = number
    max = number
  }))
  default = {
    autorizacao    = { min = 6, max = 40 }
    limites        = { min = 6, max = 40 }
    contabilizacao = { min = 2, max = 8 } # KCL: até 1 worker por shard
  }
}

variable "ambiente_autorizacao" {
  type = map(string)
}

variable "ambiente_limites" {
  type = map(string)
}

variable "ambiente_contabilizacao" {
  type = map(string)
}

variable "segredos_contabilizacao" {
  type        = map(string)
  description = "Variável de ambiente → ARN do segredo (ex.: DB_SENHA → secret:password::)."
  default     = {}
}

variable "politica_iam_autorizacao" {
  type = string
}

variable "politica_iam_limites" {
  type = string
}

variable "politica_iam_contabilizacao" {
  type = string
}
