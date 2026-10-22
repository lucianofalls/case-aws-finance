# terraform test — roda localmente, sem provider e sem conta AWS: o módulo só transforma o contrato.

variables {
  contrato_openapi      = "../../../../contracts/openapi/autorizacoes-v1.yaml"
  schema_request        = "../../../../contracts/openapi/schemas/autorizacao-request.json"
  uri_backend           = "http://nlb-interno.exemplo"
  vpc_link_id           = "vpclink-teste"
  cognito_user_pool_arn = "arn:aws:cognito-idp:sa-east-1:000000000000:userpool/sa-east-1_teste"
  respostas_gateway = {
    BAD_REQUEST_BODY = { statusCode = 422 }
  }
}

run "rota_e_versao_vem_do_contrato" {
  command = apply

  assert {
    condition     = jsondecode(output.corpo).openapi == "3.0.3"
    error_message = "o API Gateway REST só importa OpenAPI 2.0 e 3.0"
  }
  assert {
    condition     = contains(keys(jsondecode(output.corpo).paths), "/v1/contratos/{id_contrato}/autorizacoes")
    error_message = "a rota versionada do contrato deve ser publicada como está"
  }
  assert {
    condition     = output.versao_contrato == yamldecode(file(var.contrato_openapi)).info.version
    error_message = "a versão do contrato deve ser exposta"
  }
}

run "seguranca_e_validacao_na_borda" {
  command = apply

  assert {
    condition = jsondecode(output.corpo).paths["/v1/contratos/{id_contrato}/autorizacoes"].post.security[0].cognito == [
    "financas/autorizacoes.write"]
    error_message = "os escopos do Cognito devem ser os escopos OAuth2 declarados no contrato"
  }
  assert {
    condition     = contains(keys(jsondecode(output.corpo).paths["/v1/contratos/{id_contrato}/autorizacoes"].post.security[0]), "api_key")
    error_message = "a API key é obrigatória (vincula o cliente ao usage plan)"
  }
  assert {
    condition     = jsondecode(output.corpo).paths["/v1/contratos/{id_contrato}/autorizacoes"].post["x-amazon-apigateway-request-validator"] == "corpo-e-parametros"
    error_message = "corpo e parâmetros devem ser validados na borda (422)"
  }
  assert {
    condition     = jsondecode(output.corpo).components.schemas.AutorizacaoRequest.required == ["id_conta", "valor", "moeda", "tipo_operacao"]
    error_message = "o model do request deve ser o JSON Schema do contrato"
  }
  assert {
    condition     = !contains(keys(jsondecode(output.corpo).components.schemas.AutorizacaoRequest.properties.valor), "exclusiveMinimum")
    error_message = "exclusiveMinimum não é suportado pelo API Gateway"
  }
}

run "schema_externo_vira_model_importado" {
  command = apply

  assert {
    condition     = jsondecode(output.corpo).paths["/v1/contratos/{id_contrato}/autorizacoes"].post.requestBody.content["application/json"].schema["$ref"] == "#/components/schemas/AutorizacaoRequest"
    error_message = "o $ref externo deve apontar para o model importado"
  }
  assert {
    condition     = !strcontains(output.corpo, "./schemas/")
    error_message = "o API Gateway não resolve referências a arquivos externos"
  }
  assert {
    condition     = !strcontains(output.corpo, "\"examples\"")
    error_message = "exemplos são documentação do consumidor e não vão para o API Gateway"
  }
}

run "integracao_vpc_link_por_rota" {
  command = apply

  assert {
    condition     = jsondecode(output.corpo).paths["/v1/contratos/{id_contrato}/autorizacoes"].post["x-amazon-apigateway-integration"].uri == "http://nlb-interno.exemplo/v1/contratos/{id_contrato}/autorizacoes"
    error_message = "a integração deve apontar para o mesmo caminho versionado no backend"
  }
  assert {
    condition = jsondecode(output.corpo).paths["/v1/contratos/{id_contrato}/autorizacoes"].post["x-amazon-apigateway-integration"].requestParameters == {
      "integration.request.path.id_contrato" = "method.request.path.id_contrato"
    }
    error_message = "path params devem ser repassados; não injetar identidade por header"
  }
  assert {
    condition     = jsondecode(output.corpo)["x-amazon-apigateway-gateway-responses"].BAD_REQUEST_BODY.statusCode == 422
    error_message = "as gateway responses devem entrar no corpo"
  }
}
