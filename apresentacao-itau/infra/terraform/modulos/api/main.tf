# API Management (PDF §3.4) — API Gateway REST (e não HTTP API: usage plans, API keys, throttling por
# cliente, request validation, WAF e canary só existem no REST — validado na doc AWS, ADR 007).
#
#   Cliente M2M ─OAuth2 client_credentials (Cognito)─▶ WAF ─▶ REST API /v1 ─VPC Link─▶ NLB ─▶ autorizacao-service
#   Validação do corpo/headers na borda (422), usage plan por plano (429), X-Client-Id derivado do token.

data "aws_region" "atual" {}

locals {
  problema_json = "application/problem+json"
  planos = {
    bronze = { taxa = 50, rajada = 100, cota_dia = 1000000 }
    prata  = { taxa = 500, rajada = 1000, cota_dia = 20000000 }
    ouro   = { taxa = 2500, rajada = 5000, cota_dia = 200000000 }
  }
}

# ---- Identidade das aplicações clientes (OAuth2 client_credentials) -----------------------------------
resource "aws_cognito_user_pool" "clientes" {
  name = "${var.prefixo}clientes-api-financas"
}

resource "aws_cognito_resource_server" "financas" {
  user_pool_id = aws_cognito_user_pool.clientes.id
  identifier   = "financas"
  name         = "API de autorização de transações"

  scope {
    scope_name        = "autorizacoes.write"
    scope_description = "Solicitar autorizações de transação"
  }
}

resource "aws_cognito_user_pool_domain" "clientes" {
  domain       = "${var.prefixo}financas-${var.sufixo_dominio_cognito}"
  user_pool_id = aws_cognito_user_pool.clientes.id
}

resource "aws_cognito_user_pool_client" "canal_exemplo" {
  name                                 = "canal-exemplo"
  user_pool_id                         = aws_cognito_user_pool.clientes.id
  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = ["financas/autorizacoes.write"]
  access_token_validity                = 60
  token_validity_units {
    access_token = "minutes"
  }
  depends_on = [aws_cognito_resource_server.financas]
}

# ---- REST API criada a partir do contrato (contract-first) -------------------------------------------
# A rota, os parâmetros obrigatórios, o schema do request e os escopos vêm de contracts/openapi; aqui só entram
# as extensões da AWS. Mudou o contrato → muda o corpo → novo deployment.
resource "aws_api_gateway_vpc_link" "nlb" {
  name        = "${var.prefixo}financas-nlb"
  target_arns = [var.nlb_arn]
}

module "contrato" {
  source                = "../openapi-api-gateway"
  contrato_openapi      = var.contrato_openapi
  schema_request        = var.schema_request_autorizacao
  uri_backend           = "http://${var.nlb_dns_name}"
  vpc_link_id           = aws_api_gateway_vpc_link.nlb.id
  cognito_user_pool_arn = aws_cognito_user_pool.clientes.arn
  respostas_gateway     = local.respostas_gateway
}

resource "aws_api_gateway_rest_api" "financas" {
  name              = "${var.prefixo}financas-autorizacoes"
  body              = module.contrato.corpo
  put_rest_api_mode = "overwrite" # o contrato é a definição completa; nada é editado fora dele
  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

# Validação na borda devolve 422 + Problem Details (padrão do API Gateway seria 400); cota e taxa → 429.
locals {
  respostas_gateway = {
    BAD_REQUEST_BODY = {
      statusCode = 422
      responseTemplates = {
        (local.problema_json) = jsonencode({
          type           = "https://api.itau.com.br/financas/problemas/payload-invalido", title = "Payload inválido",
          status         = 422, detail = "$context.error.validationErrorString", codigo = "payload-invalido",
          correlation_id = "$context.requestId"
        })
      }
    }
    BAD_REQUEST_PARAMETERS = {
      statusCode = 422
      responseTemplates = {
        (local.problema_json) = jsonencode({
          type           = "https://api.itau.com.br/financas/problemas/requisicao-malformada", title = "Requisição malformada",
          status         = 422, detail = "$context.error.validationErrorString", codigo = "requisicao-malformada",
          correlation_id = "$context.requestId"
        })
      }
    }
    THROTTLED = {
      statusCode         = 429
      responseParameters = { "gatewayresponse.header.Retry-After" = "'1'" }
      responseTemplates = {
        (local.problema_json) = jsonencode({
          type           = "https://api.itau.com.br/financas/problemas/limite-de-requisicoes", title = "Limite de requisições",
          status         = 429, detail = "rate limit do plano excedido", codigo = "limite-de-requisicoes",
          correlation_id = "$context.requestId"
        })
      }
    }
    QUOTA_EXCEEDED = {
      statusCode = 429
      responseTemplates = {
        (local.problema_json) = jsonencode({
          type           = "https://api.itau.com.br/financas/problemas/cota-excedida", title = "Cota excedida",
          status         = 429, detail = "cota diária do plano excedida", codigo = "cota-excedida",
          correlation_id = "$context.requestId"
        })
      }
    }
  }
}

resource "aws_api_gateway_deployment" "financas" {
  rest_api_id = aws_api_gateway_rest_api.financas.id
  description = "contrato ${module.contrato.versao_contrato}"
  triggers = {
    redeploy = sha1(aws_api_gateway_rest_api.financas.body)
  }
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudwatch_log_group" "acesso" {
  name              = "/aws/apigateway/${var.prefixo}financas-autorizacoes"
  retention_in_days = 30
}

# ---- Conta do API Gateway: role para gravar logs no CloudWatch ------------------------------------------
# Configuração única por conta e região. Sem ela, o stage com logging_level falha no apply (auditoria C07).
# Se a plataforma da conta já gerencia essa role, desligar com gerenciar_conta_api_gateway = false.
resource "aws_iam_role" "api_gateway_logs" {
  count = var.gerenciar_conta_api_gateway ? 1 : 0
  name  = "${var.prefixo}api-gateway-cloudwatch"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "apigateway.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "api_gateway_logs" {
  count      = var.gerenciar_conta_api_gateway ? 1 : 0
  role       = aws_iam_role.api_gateway_logs[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs"
}

resource "aws_api_gateway_account" "conta" {
  count               = var.gerenciar_conta_api_gateway ? 1 : 0
  cloudwatch_role_arn = aws_iam_role.api_gateway_logs[0].arn
  depends_on          = [aws_iam_role_policy_attachment.api_gateway_logs]
}

# Stage = ponto de publicação, não versão: a versão major fica no path (/v1, /v2 convivem no mesmo stage).
resource "aws_api_gateway_stage" "publicado" {
  depends_on           = [aws_api_gateway_account.conta]
  rest_api_id          = aws_api_gateway_rest_api.financas.id
  deployment_id        = aws_api_gateway_deployment.financas.id
  stage_name           = "api"
  xray_tracing_enabled = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.acesso.arn
    format = jsonencode({
      requestId = "$context.requestId", clienteId = "$context.authorizer.claims.client_id",
      status    = "$context.status", latencia = "$context.responseLatency", recurso = "$context.resourcePath",
      apiKeyId  = "$context.identity.apiKeyId"
    })
  }
}

resource "aws_api_gateway_method_settings" "todos" {
  depends_on  = [aws_api_gateway_account.conta]
  rest_api_id = aws_api_gateway_rest_api.financas.id
  stage_name  = aws_api_gateway_stage.publicado.stage_name
  method_path = "*/*"
  settings {
    metrics_enabled        = true
    logging_level          = "ERROR"
    throttling_rate_limit  = var.throttling_estagio.taxa
    throttling_burst_limit = var.throttling_estagio.rajada
  }
}

# ---- Usage plans por plano de consumo (rate limit / quota por cliente → 429) --------------------------
resource "aws_api_gateway_usage_plan" "plano" {
  for_each = local.planos
  name     = "${var.prefixo}financas-${each.key}"

  api_stages {
    api_id = aws_api_gateway_rest_api.financas.id
    stage  = aws_api_gateway_stage.publicado.stage_name
  }

  throttle_settings {
    rate_limit  = each.value.taxa
    burst_limit = each.value.rajada
  }

  quota_settings {
    limit  = each.value.cota_dia
    period = "DAY"
  }
}

resource "aws_api_gateway_api_key" "canal_exemplo" {
  name = "${var.prefixo}canal-exemplo"
}

resource "aws_api_gateway_usage_plan_key" "canal_exemplo" {
  key_id        = aws_api_gateway_api_key.canal_exemplo.id
  key_type      = "API_KEY"
  usage_plan_id = aws_api_gateway_usage_plan.plano["prata"].id
}

# ---- WAF (OWASP gerenciado + rate-based por IP) ------------------------------------------------------
resource "aws_wafv2_web_acl" "api" {
  name  = "${var.prefixo}financas-api"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "aws-common"
    priority = 1
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-entradas-maliciosas"
    priority = 2
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-entradas-maliciosas"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "limite-por-ip"
    priority = 3
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = var.limite_waf_por_ip
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "limite-por-ip"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "financas-api"
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "api" {
  resource_arn = aws_api_gateway_stage.publicado.arn
  web_acl_arn  = aws_wafv2_web_acl.api.arn
}
