# Database-per-service (PDF §3.2):
#   autorizacoes (DynamoDB) — contexto Autorização, outbox embutido no item, TTL 35 d
#   limites      (DynamoDB) — contexto Limites, posição + operações + outbox explícito, uma partição por contrato
#   contabilizacao (RDS PostgreSQL Multi-AZ) — razão em partidas dobradas
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

# Projeção de permissões cliente/contrato/conta provisionada por fonte de identidade CORPORATIVA.
# P0 fail closed: não inferir titularidade de simples recebimento do ID do contrato.
resource "aws_dynamodb_table" "permissoes_contratos" {
  name                        = "${var.prefixo}permissoes-contratos"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "id_cliente"
  range_key                   = "id_contrato"
  deletion_protection_enabled = var.protecao_contra_exclusao
  attribute {
    name = "id_cliente"
    type = "S"
  }
  attribute {
    name = "id_contrato"
    type = "S"
  }
  point_in_time_recovery { enabled = true }
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

# ---- Amazon RDS PostgreSQL (NÃO Aurora) — writer e standby Multi-AZ síncrono ---------
resource "aws_db_subnet_group" "contabilizacao" {
  count      = var.habilitar_rds_postgres ? 1 : 0
  name       = "${var.prefixo}contabilizacao"
  subnet_ids = var.subnets_privadas
}
resource "aws_security_group" "postgres" {
  count       = var.habilitar_rds_postgres ? 1 : 0
  name        = "${var.prefixo}rds-postgres-contabilizacao"
  description = "PostgreSQL 5432 de SGs autorizados EKS e Lambda conciliação, nunca CIDR VPC inteira"
  vpc_id      = var.vpc_id
  dynamic "ingress" {
    for_each = toset(var.origens_rds_security_groups)
    content {
      from_port       = 5432
      to_port         = 5432
      protocol        = "tcp"
      security_groups = [ingress.value]
    }
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
resource "aws_db_instance" "contabilizacao" {
  count                           = var.habilitar_rds_postgres ? 1 : 0
  identifier                      = "${var.prefixo}contabilizacao"
  engine                          = "postgres"
  engine_version                  = var.versao_postgres
  instance_class                  = var.classe_instancia_postgres
  allocated_storage               = var.armazenamento_gb
  max_allocated_storage           = var.armazenamento_max_gb
  storage_type                    = "gp3"
  iops                            = var.iops_postgres
  storage_throughput              = var.throughput_postgres
  multi_az                        = true
  publicly_accessible             = false
  db_name                         = "contabilizacao"
  username                        = "contabilizacao_admin"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = var.kms_key_arn
  db_subnet_group_name            = aws_db_subnet_group.contabilizacao[0].name
  vpc_security_group_ids          = [aws_security_group.postgres[0].id]
  storage_encrypted               = true
  kms_key_id                      = var.kms_key_arn
  backup_retention_period         = 30
  deletion_protection             = var.protecao_contra_exclusao
  skip_final_snapshot             = !var.protecao_contra_exclusao
  final_snapshot_identifier       = var.protecao_contra_exclusao ? "${var.prefixo}contabilizacao-final" : null
  auto_minor_version_upgrade      = true
  performance_insights_enabled    = true
  performance_insights_kms_key_id = var.kms_key_arn
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  copy_tags_to_snapshot           = true
  apply_immediately               = false
}
