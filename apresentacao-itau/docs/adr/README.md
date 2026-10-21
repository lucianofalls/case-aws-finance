# Architecture Decision Records

Formato curto (contexto → decisão → consequências). O histórico completo, com as alternativas avaliadas e as
fontes oficiais consultadas, está em `../../../docs/MEMORIA.md` §5.

| # | Decisão | Pilar WA | Status |
|---|---|---|---|
| 000 | Well-Architected (6 pilares + FSI Lens) como base; preferir serviço gerenciado | todos | Aceita |
| 001 | Bounded contexts: Autorização, Limites, Contabilização, Core Banking ACL | Op. Excelência | Aceita |
| 002 | Fargate no caminho síncrono; EKS descartado | Performance | Aceita |
| 003 | DynamoDB para limites com escrita condicional | Confiabilidade | Aceita |
| 004 | Outbox com EventBridge Pipes | Confiabilidade | **Substituída pela 026** |
| 005 | Idempotência com item próprio + TTL | Confiabilidade | **Substituída pela 027** |
| 006 | EventBridge + SQS FIFO para todo o fan-out | Confiabilidade | **Substituída pela 028** |
| 007 | API Gateway REST + Cognito + WAF + usage plans | Segurança | Aceita |
| 008 | Aurora PostgreSQL na Contabilização | Performance | Aceita (I/O-Optimized, ver 030) |
| 009 | Sem service mesh; ECS Service Connect (App Mesh descontinuado em 30/09/2026) | Op. Excelência | Aceita |
| 010 | Multi-AZ ativo; DR regional ativo-passivo (MRSC indisponível em sa-east-1) | Confiabilidade | Aceita |
| 011 | BIAN v12/v13 validado como referência | Op. Excelência | Aceita |
| 012 | Mini-saga com PENDENTE + reconciliador por índice | Confiabilidade | **Substituída pela 027** |
| 013 | Single-table com GSI por contrato | Performance | **Substituída pela 027** |
| 014 | CloudEvents 1.0, dinheiro como string, Glue Schema Registry (FULL — ajustado em 21/10) | Op. Excelência | Aceita |
| 018 | Position Keeping no contexto Limites | Confiabilidade | Aceita |
| 019 | Java 21 | Op. Excelência | Aceita |
| 020 | IaC com AWS CDK | Op. Excelência | **Substituída pela 029** |
| 021 | Semântica de `tipo_operacao` e limite reduzido abaixo do utilizado | — | Aceita |
| 022 | Lambda avaliado e mantido fora do caminho síncrono | Performance, Custo | Aceita |
| 023 | Compute híbrido com regra explícita; contabilização em Fargate | Custo | Aceita |
| 024 | Concorrência sem lock distribuído (catálogo C1–C11) | Confiabilidade | Aceita |
| 025 | Único acoplamento síncrono é autorização → limites | Confiabilidade | Aceita |
| **026** | Outbox embutido + relay Lambda | Custo, Confiabilidade | Aceita |
| **027** | id determinístico, sem PENDENTE, verificação posterior com janelas disjuntas | Custo, Confiabilidade | Aceita |
| **028** | Kinesis no fluxo transacional, EventBridge na integração | Custo, Confiabilidade | Aceita |
| **029** | Terraform | Op. Excelência | Aceita |
| **030** | Aurora I/O-Optimized e TTL de 35 dias no DynamoDB | Custo | Aceita |
| **031** | API Gateway criado por importação do OpenAPI; oasdiff barra breaking change | Op. Excelência, Confiabilidade | Aceita |
| **032–035** | Agendamento de verificação, reconciliação e expiração (ver implementação e `docs/MEMORIA.md`) | Confiabilidade | Existentes no ZIP, catalogação completa pendente |
| **036** | [Hardening da compensação e conciliação](036-hardening-compensacao.md) | Confiabilidade, Segurança | Implementação revisada; testes integrais pendentes |

---

## 026 — Outbox embutido no item + relay Lambda

**Contexto.** O evento precisa sair se, e somente se, a decisão foi gravada (sem dual-write). O Pipes resolve,
mas cobra US$ 0,69/milhão: ~US$ 2,7 mil/mês por evento por autorização no volume assumido.
**Decisão.** Autorizações: atributos `evento_id/evento_tipo/evento_ocorrido_em` no próprio item (uma escrita
atômica); o relay compara `evento_id` novo × antigo e publica. Limites: item `OUT#` com o CloudEvent dentro da
mesma transação (baixo volume). Relay Lambda em lote com falha parcial e filtros no event source mapping.
**Consequências.** + custo de dezenas de dólares; funciona no LocalStack. − código a manter (pequeno, testado);
o mapeamento item → CloudEvent fica no relay, coberto por teste de contrato.

## 027 — id determinístico, sem PENDENTE, verificação posterior com janelas disjuntas

**Contexto.** Gravar PENDENTE + índice de pendentes custava ~3 WRU em toda transação, para um caso raro
(timeout depois de o Limites aplicar a operação).
**Decisão.** `id_autorizacao = UUIDv5(cliente, Idempotency-Key)`. O Limites é idempotente pelo mesmo id e
guarda a fotografia da posição. A decisão é gravada uma vez (`attribute_not_exists`). Em resultado incerto, a
autorização agenda uma verificação (SQS, atraso 15 min). O reconciliador reverte a operação órfã. O Limites só
aceita repetição até **10 min**: janelas disjuntas ⇒ retentativa do cliente e reversão nunca se cruzam.
**Consequências.** + ~−3 WRU/aut.; caminho feliz com 1 escrita na autorização. − o limite de uma operação órfã
fica preso até 15 min; clientes devem retentar resultados incertos em até 10 min com a mesma chave (documentado
no OpenAPI). Queda abrupta da task antes de agendar a verificação: mitigada por shutdown gracioso; cobertura
residual por reconciliação diária (trilha S3 × operações), fora do caminho crítico.

## 028 — Kinesis no fluxo transacional, EventBridge na integração

**Contexto.** Quotas padrão do EventBridge em sa-east-1 (PutEvents 600/s, 1.100 invocações/s, 5 destinos por
regra) e custo de US$ 1/milhão contra 5.000 eventos/s de pico; o PDF pede avaliar SQS/Kinesis/MSK para alta
volumetria.
**Decisão.** `TransacaoAutorizada/Recusada/EstornoRealizado` → Kinesis (partition key `id_contrato`, 8 shards,
7 dias). EventBridge para `LimiteConcedido/LimiteAlterado/OperacaoRevertida`, com regras, archive e DLQ.
**Consequências.** + ~US$ 0,7 mil/mês, replay por consumidor. **Ordem por contrato não é garantida**
(`PutRecords` não garante ordem e o relay processa itens em paralelo); os consumidores são independentes de
ordem, como o PDF exige (§2.4) — corrigido após a auditoria (C02). − capacidade em shards a gerir (resharding
antes de picos; alarme de `WriteProvisionedThroughputExceeded`). Alternativa válida: EventBridge com aumento
de quota, se o custo for aceito.

## 029 — Terraform

**Contexto.** Decisão do time (substitui o CDK). O PDF só exige "IaC".
**Decisão.** Módulos `rede`, `dados`, `eventos`, `compute`, `funcoes`, `api`, `observabilidade`; ambientes `prod`
(sa-east-1) e `local` (LocalStack) reutilizando os mesmos módulos, com flags para recursos sem suporte local.
**Consequências.** + padrão de mercado, multi-conta, `plan` revisável em PR. − estado remoto a gerir (S3 + lock).

## 030 — Aurora I/O-Optimized e TTL de 35 dias no DynamoDB

**Contexto.** ~4 inserts por fato contábil tornariam o I/O avulso o maior custo do Aurora; sem TTL, o DynamoDB
cresceria ~6 TB/mês.
**Decisão.** `storage_type = aurora-iopt1`; TTL de 35 dias (janela de estorno) em autorizações e operações;
histórico na trilha S3 (WORM, Glacier IR após 90 dias).
**Consequências.** + custo previsível e estável. − consultas antigas vão para Athena sobre S3, não para o DynamoDB.

## 031 — API Gateway criado por importação do OpenAPI; oasdiff barra breaking change

**Contexto.** O PDF pede contract-first com OpenAPI. A API era montada recurso por recurso no Terraform, e só o
schema do request vinha do contrato: rota, parâmetros e escopos podiam divergir do OpenAPI publicado. A AWS não
tem registry de OpenAPI com regra de compatibilidade (o Glue cobre só os eventos).
**Decisão.** O módulo `openapi-api-gateway` lê `contracts/openapi/autorizacoes-v1.yaml` e gera o `body` do
`aws_api_gateway_rest_api`, acrescentando só as extensões `x-amazon-apigateway-*` (Cognito, API key, validador,
VPC Link, gateway responses). O contrato passou para OpenAPI 3.0.3 (o API Gateway REST importa só 2.0 e 3.0) e
`exclusiveMinimum` virou `minimum: 0.01`. Breaking change é barrada no CI com oasdiff
(`contracts/verificar-contrato-api.sh`). Documentation versions e API Gateway portals ficam como recomendação.
**Consequências.** + a API publicada é o contrato; mudança no contrato gera deployment novo; tradução coberta
por `terraform test`. − exemplos e schemas de resposta não vão para o API Gateway (só documentação); o portal não
é criado pelo Terraform; o oasdiff não foi executado nesta máquina.

