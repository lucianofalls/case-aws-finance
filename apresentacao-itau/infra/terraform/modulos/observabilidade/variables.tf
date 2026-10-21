variable "prefixo" {
  type    = string
  default = ""
}

variable "nome_api" {
  type = string
}

variable "stage_api" {
  type = string
}

variable "tabelas_dynamodb" {
  type = list(string)
}

variable "funcoes_relay" {
  type = list(string)
}

variable "stream_transacoes" {
  type = string
}

variable "dlqs" {
  type        = map(string)
  description = "Rótulo → nome da fila (DLQ/quarentena)."
}
