terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.15, < 7.0"
    }
  }
  # Estado remoto (criar o bucket antes
  # Lock nativo do S3:
  # backend "s3" {
  #   bucket       = "itau-financas-terraform-state"
  #   key          = "plataforma-limites-autorizacao/prod.tfstate"
  #   region       = "sa-east-1"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.regiao
  default_tags {
    tags = {
      Sistema       = "plataforma-limites-autorizacao"
      Dominio       = "financas"
      Ambiente      = "prod"
      CentroDeCusto = var.centro_de_custo # governança de custo por domínio (Cost Explorer / Budgets)
      GerenciadoPor = "terraform"
    }
  }
}

data "aws_caller_identity" "atual" {}

data "aws_availability_zones" "disponiveis" {
  state = "available"
}

locals {
  raiz = abspath("${path.module}/../../../..")
}

# CMK do domínio financeiro, com rotação anual automática (Segurança).
resource "aws_kms_key" "financas" {
  description             = "Dados da plataforma de limites e autorização"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "financas" {
  name          = "alias/financas-limites-autorizacao"
  target_key_id = aws_kms_key.financas.key_id
}

module "rede" {
  source = "../../modulos/rede"
  nome   = "financas-prod"
  zonas  = slice(data.aws_availability_zones.disponiveis.names, 0, 3)
}

module "dados" {
  source           = "../../modulos/dados"
  modo_cobranca    = var.modo_cobranca_dynamodb
  kms_key_arn      = aws_kms_key.financas.arn
  vpc_id           = module.rede.vpc_id
  cidr_vpc         = module.rede.cidr
  subnets_privadas = module.rede.subnets_privadas
  origens_rds_security_groups = [module.eks.security_group_cluster_id, aws_security_group.lambda_interna.id]
}

module "eventos" {
  # ADR-040: manter OFF até quota EventBridge/latência/custos validados
  # Não altera o caminho transacional.
  habilitar_eventbridge_transacoes = var.habilitar_eventbridge_transacoes
  source            = "../../modulos/eventos"
  kms_key_arn       = aws_kms_key.financas.arn
  shards_transacoes = var.shards_transacoes
  diretorio_schemas = "${local.raiz}/contracts/schemas/eventos"
}

# EKS Auto Mode (não ECS). NLB interno é criado antes dos Pods e recebe IPs via TargetGroupBinding.
module "eks" {
  source           = "../../modulos/eks"
  vpc_id           = module.rede.vpc_id
  cidr_vpc         = module.rede.cidr
  subnets_privadas = module.rede.subnets_privadas
  kms_key_arn      = aws_kms_key.financas.arn
  versao_kubernetes = var.versao_kubernetes
}

resource "aws_security_group" "lambda_interna" {
  name = "financas-lambda-interna"
  vpc_id = module.rede.vpc_id
  description = "Reconciliador para NLB interno e RDS Postgres"
  egress { from_port=0
 to_port=0
 protocol="-1"
 cidr_blocks=["0.0.0.0/0"] }
}

# Mesmo conjunto de ações IAM antes anexado às tarefas ECS, agora por ServiceAccount via EKS Pod Identity.
# Revalidar estaticamente com iam-policy-autopilot antes de qualquer ambiente AWS.
locals {
  politicas_pod = {
    autorizacao = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["dynamodb:GetItem", "dynamodb:PutItem"], Resource = module.dados.tabela_autorizacoes.arn },
      { Effect = "Allow", Action = ["dynamodb:GetItem"], Resource = module.dados.tabela_permissoes_contratos.arn },
      { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"], Resource = module.eventos.expiracao_de_reservas.fila_arn }, # ADR 035
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = aws_kms_key.financas.arn },
    ]
  })
    limites = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"], Resource = module.dados.tabela_limites.arn },
      { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"], Resource = module.eventos.filas["limites-concedidos"].arn },
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = aws_kms_key.financas.arn },
    ]
  })
    contabilizacao = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["kinesis:DescribeStream", "kinesis:DescribeStreamSummary", "kinesis:GetRecords", "kinesis:GetShardIterator",
        "kinesis:ListShards", "kinesis:SubscribeToShard", "kinesis:RegisterStreamConsumer", "kinesis:DescribeStreamConsumer", "kinesis:ListStreamConsumers"]
        Resource = [module.eventos.stream_transacoes.arn, "${module.eventos.stream_transacoes.arn}/consumer/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:Scan", "dynamodb:Query", "dynamodb:DescribeTable"]
        Resource = module.dados.tabela_leases_kcl.arn
      },
      { Effect = "Allow", Action = ["cloudwatch:PutMetricData"], Resource = "*" },
      { Effect = "Allow", Action = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"], Resource = module.eventos.fila_quarentena_contabilizacao.arn }, # envia e reprocessa (C06)
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = aws_kms_key.financas.arn },
    ]
  })
  }
}
resource "aws_iam_role" "pod" {
  for_each = local.politicas_pod
  name = "financas-${each.key}-pod"
  assume_role_policy = jsonencode({ Version="2012-10-17", Statement=[{
    Effect="Allow", Principal={Service="pods.eks.amazonaws.com"}, Action=["sts:AssumeRole", "sts:TagSession"]
  }]})
}
resource "aws_iam_role_policy" "pod" {
  for_each = local.politicas_pod
  role = aws_iam_role.pod[each.key].id
  policy = each.value
}
resource "aws_eks_pod_identity_association" "servicos" {
  for_each = local.politicas_pod
  cluster_name = module.eks.cluster_name
  namespace = "financas"
  service_account = each.key
  role_arn = aws_iam_role.pod[each.key].arn
}

module "funcoes" {
  source               = "../../modulos/funcoes"
  diretorio_artefatos  = var.diretorio_artefatos
  tabela_autorizacoes  = module.dados.tabela_autorizacoes
  tabela_limites       = module.dados.tabela_limites
  stream_transacoes    = module.eventos.stream_transacoes
  barramento           = module.eventos.barramento
  fila_verificacao_arn = module.eventos.filas["autorizacoes-verificacao"].arn
  fila_verificacao_url = module.eventos.filas["autorizacoes-verificacao"].url
  expiracao = {
    grupo     = module.eventos.expiracao_de_reservas.grupo
    papel_arn = module.eventos.expiracao_de_reservas.papel_arn
    fila_arn  = module.eventos.expiracao_de_reservas.fila_arn
    dlq_arn   = module.eventos.expiracao_de_reservas.dlq_arn
  }
  rds_postgres = {
    endereco = module.dados.rds_postgres.endereco
    segredo_senha_arn = module.dados.rds_postgres.segredo_senha_arn
    identificador = module.dados.rds_postgres.identificador
  }
  conciliacao = {
    bucket_nome = module.conciliacao.bucket.nome
    bucket_arn  = module.conciliacao.bucket.arn
    banco_glue  = module.conciliacao.banco_glue
  }
  fila_corebanking_arn   = module.eventos.filas["corebanking-contratos"].arn
  fila_falhas_relay_arn  = module.eventos.fila_falhas_relay_arn
  url_interna_limites    = module.eks.url_interna_limites
  subnets_privadas       = module.rede.subnets_privadas
  security_group_lambdas = aws_security_group.lambda_interna.id
  kms_key_arn            = aws_kms_key.financas.arn
}

# Conciliação diária — BIAN Account Reconciliation (ADR 034): garantia final contra perda silenciosa de eventos
# e operações órfãs, comparando fontes independentes do transporte (exportações DynamoDB, trilha S3, razão).
module "conciliacao" {
  source              = "../../modulos/conciliacao"
  kms_key_arn         = aws_kms_key.financas.arn
  tabela_autorizacoes = { nome = module.dados.tabela_autorizacoes.nome, arn = module.dados.tabela_autorizacoes.arn }
  tabela_limites      = { nome = module.dados.tabela_limites.nome, arn = module.dados.tabela_limites.arn }
  bucket_trilha       = module.eventos.bucket_trilha
  rds_postgres = {
    arn               = module.dados.rds_postgres.arn
    identificador     = module.dados.rds_postgres.identificador
    segredo_senha_arn = module.dados.rds_postgres.segredo_senha_arn
  }
  funcoes = {
    preparar_janela = module.funcoes.arns["preparar-janela"]
    republicador    = module.funcoes.arns["republicador"]
    agendar_orfas   = module.funcoes.arns["agendar-orfas"]
    exportar_razao  = module.funcoes.arns["exportar-razao"]
  }
  topico_alarmes_arn = module.observabilidade.topico_alarmes_arn
}

module "api" {
  source                     = "../../modulos/api"
  nlb_arn                    = module.eks.nlb.arn
  nlb_dns_name               = module.eks.nlb.dns_name
  contrato_openapi           = "${local.raiz}/contracts/openapi/autorizacoes-v1.yaml"
  schema_request_autorizacao = "${local.raiz}/contracts/openapi/schemas/autorizacao-request.json"
  sufixo_dominio_cognito     = data.aws_caller_identity.atual.account_id
}

module "observabilidade" {
  source            = "../../modulos/observabilidade"
  nome_api          = module.api.nome_api
  stage_api         = module.api.stage_api
  tabelas_dynamodb  = [module.dados.tabela_autorizacoes.nome, module.dados.tabela_limites.nome]
  funcoes_relay     = [module.funcoes.funcoes["relay-autorizacoes"], module.funcoes.funcoes["relay-limites"], module.funcoes.funcoes["agendador-verificacao"]]
  stream_transacoes = module.eventos.stream_transacoes.nome
  dlqs              = module.eventos.dlq_nomes
}
