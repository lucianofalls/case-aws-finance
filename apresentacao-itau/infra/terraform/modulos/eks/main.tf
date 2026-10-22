# EKS Auto Mode privado / Pod Identity / NLB criado no Terraform.
# Pods ligados às target groups com TargetGroupBinding (eks.amazonaws.com/v1).
# Não usar ECS Fargate neste ambiente. EKS Auto Mode gerencia compute/storage/networking.

data "aws_region" "atual" {}

locals {
  nome_cluster = "${var.prefixo}financas-eks"
  servicos     = toset(["autorizacao", "limites", "contabilizacao"])
}

resource "aws_iam_role" "cluster" {
  name = "${var.prefixo}eks-cluster"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Principal = { Service = "eks.amazonaws.com" }, Action = ["sts:AssumeRole", "sts:TagSession"]
  }] })
}

resource "aws_iam_role_policy_attachment" "cluster" {
  for_each = toset(["AmazonEKSClusterPolicy", "AmazonEKSComputePolicy", "AmazonEKSBlockStoragePolicyV2",
  "AmazonEKSNetworkingPolicy", "AmazonEKSLoadBalancingPolicy"])
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/${each.key}"
}

resource "aws_iam_role" "node" {
  name = "${var.prefixo}eks-auto-node"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole"
  }] })
}
resource "aws_iam_role_policy_attachment" "node" {
  for_each   = toset(["AmazonEKSWorkerNodeMinimalPolicy", "AmazonEC2ContainerRegistryPullOnly"])
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/${each.key}"
}

resource "aws_cloudwatch_log_group" "audit" {
  name              = "/aws/eks/${local.nome_cluster}/cluster"
  retention_in_days = 90
}

resource "aws_eks_cluster" "this" {
  name                          = local.nome_cluster
  role_arn                      = aws_iam_role.cluster.arn
  version                       = var.versao_kubernetes
  bootstrap_self_managed_addons = false
  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true # bootstrap
    # Migrar bootstrap admin para EKS access entries de IAM corporativo.
  }
  vpc_config {
    subnet_ids              = var.subnets_privadas
    endpoint_private_access = true
    endpoint_public_access  = false
  }
  compute_config {
    enabled       = true
    node_pools    = ["general-purpose", "system"]
    node_role_arn = aws_iam_role.node.arn
  }
  kubernetes_network_config {
    elastic_load_balancing { enabled = true }
  }
  storage_config {
    block_storage { enabled = true }
  }
  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
  depends_on = [aws_iam_role_policy_attachment.cluster, aws_iam_role_policy_attachment.node,
  aws_cloudwatch_log_group.audit]
}

# ECR com imagens imutáveis
# Publicação de imagens por pipeline manual, sem GitHub Actions.
resource "aws_ecr_repository" "servico" {
  for_each             = local.servicos
  name                 = "${var.prefixo}${each.key}-service"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

# API Gateway REST VPC Link requer ARN de NLB previamente conhecido.
# TargetGroupBinding do EKS Auto Mode registra IPs dos Pods nas target groups já criadas.
resource "aws_security_group" "nlb" {
  name        = "${var.prefixo}eks-vpc-link-nlb"
  description = "API Gateway private VPC Link and reconciler to internal NLB"
  vpc_id      = var.vpc_id
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
    description = "API Gateway VPC Link within VPC"
  }
  ingress {
    from_port   = 8081
    to_port     = 8081
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
    description = "Reconciliation Lambda within VPC"
  }
  egress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }
}

resource "aws_lb" "interno" {
  name                             = "${var.prefixo}financas-eks"
  internal                         = true
  load_balancer_type               = "network"
  subnets                          = var.subnets_privadas
  security_groups                  = [aws_security_group.nlb.id]
  enable_cross_zone_load_balancing = true
}

resource "aws_lb_target_group" "servico" {
  for_each             = { autorizacao = 80, limites = 8081 }
  name                 = "${var.prefixo}${each.key}-eks"
  vpc_id               = var.vpc_id
  port                 = 8080
  protocol             = "TCP"
  target_type          = "ip"
  deregistration_delay = 30
  health_check {
    protocol = "HTTP"
    path     = "/actuator/health/readiness"
    port     = "traffic-port"
  }
  tags = { "eks:eks-cluster-name" = aws_eks_cluster.this.name }
  lifecycle { prevent_destroy = true } # Auto Mode TargetGroupBinding também reconcilia o target group
}

resource "aws_lb_listener" "servico" {
  for_each          = aws_lb_target_group.servico
  load_balancer_arn = aws_lb.interno.arn
  port              = each.key == "autorizacao" ? 80 : 8081
  protocol          = "TCP"
  default_action {
    type             = "forward"
    target_group_arn = each.value.arn
  }
}
