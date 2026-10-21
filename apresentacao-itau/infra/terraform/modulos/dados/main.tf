# Database-per-service (PDF §3.2):
#   autorizacoes (DynamoDB) — contexto Autorização, outbox embutido no item, TTL 35 d
#   limites      (DynamoDB) — contexto Limites, posição + operações + outbox explícito, uma partição por contrato
#   contabilizacao (Aurora PostgreSQL) — razão em partidas dobradas
#
# Custo (docs/custos.md): em carga estável, PROVISIONED + auto scaling custa ~3x menos que on-demand.
# On-demand fica para ambientes novos/imprevisíveis. Antes de picos planejados (Black Friday), pré-aquecer
# com warm throughput (operação documentada no runbook).

locals {
  provisionado = var.modo_cobranca == "PROVISIONED"
  tabelas = {
    autorizacoes = aws_dynamodb_table.autorizacoes
    limites      = aws_dynamodb_table.limites
  }
}

resource "aws_dynamodb_table" "autorizacoes" {
  name                        = "${var.prefixo}autorizacoes"
  billing_mode                = var.modo_cobranca
  read_capacity               = local.provisionado ? var.capacidade.autorizacoes.leitura_min : null
  write_capacity              = local.provisionado ? var.capacidade.autorizacoes.escrita_min : null
  hash_key                    = "id_autorizacao"
  stream_enabled              = true
  stream_view_type            = "NEW_AND_OLD_IMAGES" # o relay compara evento_id novo × antigo
  deletion_protection_enabled = var.protecao_contra_exclusao

  attribute {
    name = "id_autorizacao"
    type = "S"
  }

  ttl {
    attribute_name = "expira_em"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  lifecycle {
    ignore_changes = [read_capacity, write_capacity] # geridos pelo auto scaling
  }
}

resource "aws_dynamodb_table" "limites" {
  name                        = "${var.prefixo}limites"
  billing_mode                = var.modo_cobranca
  read_capacity               = local.provisionado ? var.capacidade.limites.leitura_min : null
  write_capacity              = local.provisionado ? var.capacidade.limites.escrita_min : null
  hash_key                    = "id_contrato"
  range_key                   = "sk"
  stream_enabled              = true
  stream_view_type            = "NEW_IMAGE" # só itens OUT# interessam ao relay (filtro no event source mapping)
  deletion_protection_enabled = var.protecao_contra_exclusao

  attribute {
    name = "id_contrato"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  ttl {
    attribute_name = "expira_em"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  lifecycle {
    ignore_changes = [read_capacity, write_capacity]
  }
}

# Tabela de leases do KCL (contabilização): pré-criada para não dar CreateTable à task (least privilege).
resource "aws_dynamodb_table" "leases_kcl" {
  name         = var.nome_aplicacao_kcl
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "leaseKey"

  attribute {
    name = "leaseKey"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

# ---- Auto scaling (só no modo provisionado) -------------------------------------------------------------
resource "aws_appautoscaling_target" "escrita" {
  for_each           = local.provisionado ? local.tabelas : {}
  service_namespace  = "dynamodb"
  resource_id        = "table/${each.value.name}"
  scalable_dimension = "dynamodb:table:WriteCapacityUnits"
  min_capacity       = var.capacidade[each.key].escrita_min
  max_capacity       = var.capacidade[each.key].escrita_max
}

resource "aws_appautoscaling_policy" "escrita" {
  for_each           = aws_appautoscaling_target.escrita
  name               = "${each.key}-escrita-70"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = each.value.service_namespace
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value = 70
    predefined_metric_specification {
      predefined_metric_type = "DynamoDBWriteCapacityUtilization"
    }
  }
}

resource "aws_appautoscaling_target" "leitura" {
  for_each           = local.provisionado ? local.tabelas : {}
  service_namespace  = "dynamodb"
  resource_id        = "table/${each.value.name}"
  scalable_dimension = "dynamodb:table:ReadCapacityUnits"
  min_capacity       = var.capacidade[each.key].leitura_min
  max_capacity       = var.capacidade[each.key].leitura_max
}

resource "aws_appautoscaling_policy" "leitura" {
  for_each           = aws_appautoscaling_target.leitura
  name               = "${each.key}-leitura-70"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = each.value.service_namespace
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value = 70
    predefined_metric_specification {
      predefined_metric_type = "DynamoDBReadCapacityUtilization"
    }
  }
}

# ---- Aurora PostgreSQL (contabilização) ------------------------------------------------------------------
resource "aws_db_subnet_group" "contabilizacao" {
  count      = var.habilitar_aurora ? 1 : 0
  name       = "${var.prefixo}contabilizacao"
  subnet_ids = var.subnets_privadas
}

resource "aws_security_group" "aurora" {
  count       = var.habilitar_aurora ? 1 : 0
  name        = "${var.prefixo}aurora-contabilizacao"
  description = "PostgreSQL apenas a partir da VPC"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }
}

resource "aws_rds_cluster" "contabilizacao" {
  count                               = var.habilitar_aurora ? 1 : 0
  cluster_identifier                  = "${var.prefixo}contabilizacao"
  engine                              = "aurora-postgresql"
  engine_version                      = var.versao_aurora
  database_name                       = "contabilizacao"
  master_username                     = "contabilizacao_admin"
  manage_master_user_password         = true # senha no Secrets Manager com rotação
  iam_database_authentication_enabled = true
  enable_http_endpoint                = true           # Data API: a conciliação exporta o razão para o S3 sem Lambda na VPC (ADR 034)
  storage_type                        = "aurora-iopt1" # I/O-Optimized: ~4 inserts/transação tornariam o I/O avulso o maior custo
  storage_encrypted                   = true
  kms_key_id                          = var.kms_key_arn
  db_subnet_group_name                = aws_db_subnet_group.contabilizacao[0].name
  vpc_security_group_ids              = [aws_security_group.aurora[0].id]
  backup_retention_period             = 14
  deletion_protection                 = var.protecao_contra_exclusao
  copy_tags_to_snapshot               = true
  skip_final_snapshot                 = !var.protecao_contra_exclusao
  final_snapshot_identifier           = var.protecao_contra_exclusao ? "${var.prefixo}contabilizacao-final" : null
}

resource "aws_rds_cluster_instance" "contabilizacao" {
  count                        = var.habilitar_aurora ? 2 : 0 # writer + reader em AZs distintas (Multi-AZ)
  identifier                   = "${var.prefixo}contabilizacao-${count.index}"
  cluster_identifier           = aws_rds_cluster.contabilizacao[0].id
  engine                       = aws_rds_cluster.contabilizacao[0].engine
  engine_version               = aws_rds_cluster.contabilizacao[0].engine_version
  instance_class               = var.classe_instancia_aurora # Graviton (r7g): custo e sustentabilidade
  performance_insights_enabled = true
}
