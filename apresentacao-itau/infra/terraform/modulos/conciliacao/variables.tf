variable "prefixo" {
  type    = string
  default = ""
}

variable "kms_key_arn" {
  type = string
}

variable "tabela_autorizacoes" {
  type = object({ nome = string, arn = string })
}

variable "tabela_limites" {
  type = object({ nome = string, arn = string })
}

variable "bucket_trilha" {
  description = "Trilha WORM do stream transacoes (Firehose), usada como prova de publicação."
  type        = object({ nome = string, arn = string })
}

variable "rds_postgres" {
  description = "Instância RDS PostgreSQL (sem Data API) e segredo do usuário administrador."
  type        = object({ arn = string, identificador = string, segredo_senha_arn = string })
}

variable "funcoes" {
  description = "ARN (alias) das Lambdas da conciliação."
  type        = object({ preparar_janela = string, republicador = string, agendar_orfas = string, exportar_razao=string })
}

variable "topico_alarmes_arn" {
  type = string
}

variable "agendamento" {
  description = "Horário da conciliação diária (UTC). 03:00 UTC = 00:00 em Brasília: concilia o dia anterior fechado."
  type        = string
  default     = "cron(0 3 * * ? *)"
}
