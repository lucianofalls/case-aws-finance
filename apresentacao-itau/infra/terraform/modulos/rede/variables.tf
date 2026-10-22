variable "nome" {
  type        = string
  description = "Prefixo dos recursos de rede."
}

variable "cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "zonas" {
  type        = list(string)
  description = "Três AZs da região (Multi-AZ ativo)."
}

variable "interface_endpoints" {
  type        = list(string)
  description = "Serviços acessados por PrivateLink (sem NAT)."
  default = [
    "ecr.api", "ecr.dkr", "logs", "monitoring", "xray", "sts", "kms", "secretsmanager",
    "kinesis-streams", "sqs", "events", "elasticloadbalancing", "ec2", "eks", "eks-auth"
  ]
}
