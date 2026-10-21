output "vpc_id" {
  value = aws_vpc.this.id
}

output "cidr" {
  value = aws_vpc.this.cidr_block
}

output "subnets_privadas" {
  value = aws_subnet.privada[*].id
}
