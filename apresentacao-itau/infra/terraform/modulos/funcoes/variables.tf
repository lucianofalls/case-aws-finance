variable "prefixo" {
  type    = string
  default = ""
}

variable "diretorio_artefatos" {
  type        = string
  description = "Diretório com os JARs das Lambdas (saída do mvn package)."
}

variable "tabela_autorizacoes" {
  type = object({ nome = string, arn = string, stream_arn = string })
}

variable "tabela_limites" {
  type = object({ nome = string, arn = string, stream_arn = string })
}

variable "stream_transacoes" {
  type = object({ nome = string, arn = string })
}

variable "barramento" {
  type = object({ nome = string, arn = string })
}

variable "fila_verificacao_url" {
  type        = string
  description = "URL da fila autorizacoes-verificacao (atraso de 15 min), alimentada pelo agendador (C01)."
}

variable "fila_verificacao_arn" {
  type = string
}

variable "fila_corebanking_arn" {
  type = string
}

variable "fila_falhas_relay_arn" {
  type = string
}

variable "url_interna_limites" {
  type = string
}

variable "subnets_privadas" {
  type    = list(string)
  default = []
}

variable "security_group_lambdas" {
  type    = string
  default = null
}

variable "kms_key_arn" {
  type    = string
  default = null
}

variable "habilitar_snapstart" {
  type    = bool
  default = true
}

variable "variaveis_extras" {
  type        = map(string)
  description = "Variáveis adicionais (ex.: AWS_ENDPOINT_URL no LocalStack)."
  default     = {}
}

variable "conciliacao" {
  description = "Conciliação diária (ADR 034): bucket e banco do Glue. null desliga as Lambdas da conciliação (ambiente local)."
  type        = object({ bucket_nome = string, bucket_arn = string, banco_glue = string })
  default     = null
}

variable "expiracao" {
  description = "Expiração de reservas (ADR 035): grupo do Scheduler, papel e fila. null desliga o agendador (local)."
  type        = object({ grupo = string, papel_arn = string, fila_arn = string, dlq_arn = string })
  default     = null
}

variable "rds_postgres" {
  type=object({endereco=string, segredo_senha_arn=string, identificador=string})
  default=null
  description="RDS PostgreSQL Multi-AZ (não Aurora): host e Secret para exportação JDBC privada."
}
