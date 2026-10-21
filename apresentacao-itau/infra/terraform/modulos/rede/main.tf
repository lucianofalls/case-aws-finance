# Rede privada em 3 AZs, sem NAT: todo acesso às APIs AWS passa por VPC endpoints
# (Segurança: tráfego não sai para a internet; Custo: gateway endpoints de DynamoDB/S3 são gratuitos).

data "aws_region" "atual" {}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.nome }
}

resource "aws_subnet" "privada" {
  count             = length(var.zonas)
  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(var.cidr, 4, count.index)
  availability_zone = var.zonas[count.index]
  tags              = { Name = "${var.nome}-privada-${var.zonas[count.index]}", Camada = "privada" }
}

resource "aws_route_table" "privada" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.nome}-privada" }
}

resource "aws_route_table_association" "privada" {
  count          = length(aws_subnet.privada)
  subnet_id      = aws_subnet.privada[count.index].id
  route_table_id = aws_route_table.privada.id
}

resource "aws_vpc_endpoint" "gateway" {
  for_each          = toset(["dynamodb", "s3"])
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.atual.name}.${each.key}"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.privada.id]
  tags              = { Name = "${var.nome}-${each.key}" }
}

resource "aws_security_group" "endpoints" {
  name        = "${var.nome}-vpc-endpoints"
  description = "HTTPS da VPC para os interface endpoints"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "HTTPS interno"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.cidr]
  }
}

resource "aws_vpc_endpoint" "interface" {
  for_each            = toset(var.interface_endpoints)
  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${data.aws_region.atual.name}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.privada[*].id
  security_group_ids  = [aws_security_group.endpoints.id]
  tags                = { Name = "${var.nome}-${each.key}" }
}
