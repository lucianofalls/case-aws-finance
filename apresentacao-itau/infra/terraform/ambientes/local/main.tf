# Ambiente local (LocalStack) — mesmos módulos de dados, eventos e funções da produção.
# Rede, ECS, API Gateway/Cognito e Aurora ficam fora: os serviços rodam no docker-compose (mesma imagem)
# e o Postgres é um container. Recursos não suportados na edição gratuita do LocalStack são desligados por flag.

terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}

provider "aws" {
  region                      = "sa-east-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  s3_use_path_style           = true

  endpoints {
    cloudwatch = var.endpoint_localstack
    dynamodb   = var.endpoint_localstack
    events     = var.endpoint_localstack
    firehose   = var.endpoint_localstack
    iam        = var.endpoint_localstack
    kinesis    = var.endpoint_localstack
    lambda     = var.endpoint_localstack
    logs       = var.endpoint_localstack
    s3         = var.endpoint_localstack
    sns        = var.endpoint_localstack
    sqs        = var.endpoint_localstack
    sts        = var.endpoint_localstack
  }
}

locals {
  raiz = abspath("${path.module}/../../../..")
}

module "dados" {
  source                   = "../../modulos/dados"
  modo_cobranca            = "PAY_PER_REQUEST"
  protecao_contra_exclusao = false
  habilitar_rds_postgres   = false
}

module "eventos" {
  source                       = "../../modulos/eventos"
  shards_transacoes            = 1
  habilitar_firehose           = false
  habilitar_schema_registry    = false
  habilitar_expiracao_agendada = false # Scheduler fora do LocalStack: a demo simula a entrega na fila
  diretorio_schemas            = "${local.raiz}/contracts/schemas/eventos"
}

module "funcoes" {
  source                = "../../modulos/funcoes"
  diretorio_artefatos   = "${local.raiz}/artefatos"
  tabela_autorizacoes   = module.dados.tabela_autorizacoes
  tabela_limites        = module.dados.tabela_limites
  stream_transacoes     = module.eventos.stream_transacoes
  barramento            = module.eventos.barramento
  fila_verificacao_arn  = module.eventos.filas["autorizacoes-verificacao"].arn
  fila_verificacao_url  = module.eventos.filas["autorizacoes-verificacao"].url
  fila_corebanking_arn  = module.eventos.filas["corebanking-contratos"].arn
  fila_falhas_relay_arn = module.eventos.fila_falhas_relay_arn
  url_interna_limites   = "http://limites:8080" # Lambdas do LocalStack na mesma rede do compose
  habilitar_snapstart   = false
  variaveis_extras      = { AWS_ENDPOINT_URL = "http://localstack:4566", AWS_CBOR_ENABLED = "false" }
}

output "filas" {
  value = module.eventos.filas
}

output "fila_quarentena_contabilizacao" {
  value = module.eventos.fila_quarentena_contabilizacao.url
}
