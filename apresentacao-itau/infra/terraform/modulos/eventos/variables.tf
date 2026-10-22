variable "prefixo" {
  type    = string
  default = ""
}

variable "shards_transacoes" {
  type        = number
  description = "Shards provisionados do stream de transações (1 MB/s e 1.000 registros/s de escrita por shard)."
  default     = 8
}

variable "kms_key_arn" {
  type    = string
  default = null
}

variable "atraso_verificacao_segundos" {
  type        = number
  description = "Janela antes do reconciliador agir; deve ser maior que a janela de repetição do Limites (10 min)."
  default     = 900

  validation {
    condition     = var.atraso_verificacao_segundos <= 900 && var.atraso_verificacao_segundos > 600
    error_message = "Use entre 601 e 900 s: maior que a janela de repetição (600 s) e dentro do máximo do SQS (900 s)."
  }
}

variable "retencao_auditoria_dias" {
  type        = number
  description = "Retenção WORM da trilha de auditoria (confirmar prazo regulatório com Compliance)."
  default     = 1825
}

variable "habilitar_firehose" {
  type    = bool
  default = true
}

variable "habilitar_schema_registry" {
  type    = bool
  default = true
}

variable "diretorio_schemas" {
  type        = string
  description = "Diretório com os JSON Schemas dos eventos (contracts/schemas)."
}

variable "habilitar_expiracao_agendada" {
  description = "Grupo e papel do EventBridge Scheduler para expirar reservas (ADR 035). false no LocalStack: a fila existe e a expiração é simulada na demo."
  type        = bool
  default     = true
}

variable "habilitar_eventbridge_transacoes" {
  description = "C04 / ADR-040: ponte Kinesis -> EventBridge Pipes para TransacaoAutorizada v1. Padrão false até revisar quota PutEvents, leituras adicionais do Kinesis e TCO."
  type        = bool
  default     = false
}
