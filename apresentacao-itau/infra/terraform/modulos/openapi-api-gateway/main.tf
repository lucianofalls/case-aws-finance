# Contract-first de verdade: o contrato público (contracts/openapi) é a fonte da API publicada.
# Este módulo não cria recursos: lê o OpenAPI e devolve o corpo que o API Gateway importa, acrescentando só o que é
# da AWS (extensões x-amazon-apigateway-*). Rotas, parâmetros, obrigatoriedade e schema do request vêm do contrato.
#
# O que sai na tradução (doc AWS, "Amazon API Gateway important notes for REST APIs"):
#   - exemplos e schemas de resposta: documentação para o consumidor; a integração HTTP_PROXY repassa a resposta
#     do serviço sem modelo (e o API Gateway não suporta `example` nem `allOf` com `$ref` em todos os casos);
#   - `$schema` do JSON Schema externo: o model do API Gateway já é draft-04.
# O que entra: Cognito authorizer com os escopos do contrato, API key (usage plan), validador de corpo e
# parâmetros, integração VPC Link por rota e as gateway responses (422/429 em Problem Details).

locals {
  contrato = yamldecode(file(var.contrato_openapi))

  modelo_request = { for chave, valor in jsondecode(file(var.schema_request)) : chave => valor if chave != "$schema" }

  escopos_padrao = local.contrato.security[0][var.esquema_oauth2_do_contrato]

  paths = {
    for caminho, metodos in local.contrato.paths : caminho => {
      for metodo, operacao in metodos : metodo => merge(
        { for chave, valor in operacao : chave => valor if !contains(["requestBody", "responses", "security"], chave) },
        {
          for chave, valor in {
            requestBody = try({
              required = operacao.requestBody.required
              content  = { for tipo, conteudo in operacao.requestBody.content : tipo => { schema = conteudo.schema } }
            }, null)
          } : chave => valor if valor != null
        },
        {
          responses = { for status, resposta in operacao.responses : status => { description = resposta.description } }
          security = [{
            cognito = try(operacao.security[0][var.esquema_oauth2_do_contrato], local.escopos_padrao)
            api_key = []
          }]
          "x-amazon-apigateway-request-validator" = var.nome_validador
          "x-amazon-apigateway-integration" = {
            type                = "http_proxy"
            httpMethod          = upper(metodo)
            uri                 = "${var.uri_backend}${caminho}"
            connectionType      = "VPC_LINK"
            connectionId        = var.vpc_link_id
            timeoutInMillis     = var.timeout_ms
            passthroughBehavior = "when_no_match"
            requestParameters = merge(
              {
                for parametro in flatten(regexall("\\{([^}]+)\\}", caminho)) :
                "integration.request.path.${parametro}" => "method.request.path.${parametro}"
              },
              { for cabecalho, origem in var.cabecalhos_do_contexto : "integration.request.header.${cabecalho}" => origem }
            )
          }
        }
      )
    }
  }

  corpo = {
    openapi = local.contrato.openapi
    info    = local.contrato.info
    paths   = local.paths
    components = {
      parameters = local.contrato.components.parameters
      schemas    = { (var.nome_modelo_request) = local.modelo_request }
      securitySchemes = {
        cognito = {
          type                           = "apiKey"
          name                           = "Authorization"
          in                             = "header"
          "x-amazon-apigateway-authtype" = "cognito_user_pools"
          "x-amazon-apigateway-authorizer" = {
            type         = "cognito_user_pools"
            providerARNs = [var.cognito_user_pool_arn]
          }
        }
        api_key = { type = "apiKey", name = "x-api-key", in = "header" }
      }
    }
    "x-amazon-apigateway-api-key-source" = "HEADER"
    "x-amazon-apigateway-request-validators" = {
      (var.nome_validador) = { validateRequestBody = true, validateRequestParameters = true }
    }
    "x-amazon-apigateway-gateway-responses" = var.respostas_gateway
  }

  # A referência ao schema externo vira referência ao model importado junto.
  corpo_json = replace(jsonencode(local.corpo), jsonencode(var.ref_schema_request),
  jsonencode("#/components/schemas/${var.nome_modelo_request}"))
}
