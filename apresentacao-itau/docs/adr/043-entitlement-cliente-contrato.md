# ADR-043 — Autorização de cliente por contrato e conta (BIAN/FSI)

**Data:** 2026-10-22. **Status:** código implementado; provisionamento real de permissões pendente.

## Contexto
Cognito OAuth2 autentica o canal, mas a autorização financeira deve impedir
BOLA/IDOR: qualquer canal com um contrato conhecido não deve conseguir movimentar seu limite.
O payload do cliente não pode ser fonte de permissão.

## Decisão
- `AutorizarTransacaoService.executar` verifica `id_cliente`, `id_contrato`, `id_conta`
  em DynamoDB `permissoes-contratos` (leitura fortemente consistente).
- Um entitlement só é válido quando `ativo=true` e `id_conta` corresponde.
- Ausência/revogação/inconsistência implica **HTTP 403 e nenhuma chamada ao Limites**.
- O ID do cliente deve vir do token Cognito, validado/injetado no API Gateway;
  o NLB é privado e não deve ser publicado fora da VPC.
- A expiração originada em SQS usa porta de aplicação **interna**, alimentada
  por uma reserva persistida previamente, não um header técnico falsificável.
- No LocalStack, o `demo.sh` provisiona permissões **sintéticas** somente para testes.

## Pendências de segurança (bloqueiam produção)
1. Definir **a fonte corporativa da concessão/revogação de entitlements** (IAM do banco/core,
   papéis de app, contratos e relacionamento entre organizações). O evento de criação de
   contrato do core não prova que qualquer canal possa acessá-lo.
2. Autenticar e autorizar também no backend: hoje `X-Client-Id` é confiado ao
   gateway. Adicionar JWT Resource Server com validação de issuer, assinatura, audience/client_id e scope
   ou mTLS/identidade end-to-end. Proibir acesso por caminhos internos alternativos.
3. Revisar segredos, revogação imediata, logs de decisões negativas (sem PII) e trilha de auditoria.
4. Simular duas contas/contratos e negar acesso cruzado em IT/E2E.

## Consequências
+ Isolamento de dados por contrato. + Falha fechada antes de qualquer movimento.
− Mais uma consulta fortemente consistente no caminho síncrono, que deve entrar no teste de 5k TPS.
− Ainda depende de um mecanismo externo de provisionamento de concessões, não simulado em produção.

**Refs:** `.../AcessoClienteContratoPort.java`, `DynamoDbAcessoClienteContratoAdapter.java`,
`infra/terraform/modulos/dados/main.tf`, `local/demo.sh`.
