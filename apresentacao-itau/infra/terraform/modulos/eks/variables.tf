variable "prefixo" { type=string
 default="" }
variable "vpc_id" { type=string }
variable "cidr_vpc" { type=string }
variable "subnets_privadas" { type=list(string) }
variable "kms_key_arn" { type=string }
variable "versao_kubernetes" {
  type=string
  description="EKS Kubernetes release em suporte na região (validar antes do plan)."
  default="1.34"
}
