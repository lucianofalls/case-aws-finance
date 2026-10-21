# ADR-037 — Evento transacional: Kinesis versus requisito de EventBridge

**Data:** 2026-10-21  
**Status:** PROPOSTA / gap contratual pendente de validação com avaliadores  
**Foco:** AWS Well-Architected (Reliability, Performance Efficiency, Cost Optimization) e contrato de eventos POD99

## Requisito do PDF original

O item **2.4** descreve explicitamente **TransacaoAutorizada (v1) — publicado em Amazon EventBridge**. O item **3.3** exige modelar EventBridge (roteamento por regras), SNS, SQS e avaliar Kinesis/MSK para alta volumetria. Os consumidores devem ser idempotentes e tolerantes a reordenação.

## O que o código entrega hoje

- Fatos transacionais de Autorização são persistidos em DynamoDB com um Outbox embutido e, após o commit, um relay Lambda publica em **Kinesis Data Streams**.
- Contabilização usa KCL com Inbox/lançamentos PostgreSQL; antifraude e notificações têm consumidores stub; Firehose/S3 preserva auditoria.
- **EventBridge** é utilizado para eventos de Limites e integração com Core Banking ACL.
- **Amazon SNS** está definido para alarmes operacionais; o fan-out de negócio de notificações é realizado hoje por consumidor Kinesis stub, não por uma topologia SNS→SQS de negócio.
- O sistema **não demonstra TransacaoAutorizada v1 no EventBridge**, em divergência literal com o exemplo do item 2.4.

## Decisões e alternativas

**Alternativa A — Compatibilidade literal com o case (recomendada para fechamento da defesa):**

Adicionar, após o Kinesis, uma ponte de publicação no EventBridge para o CloudEvent `TransacaoAutorizada`, preservando o mesmo `event_id`, `correlation_id`, schema versionado e entrega at-least-once. A ponte deve:
1. Fazer fan-out para regras EventBridge/SQS com fonte e tipo versionados.
2. Usar leitura do stream com checkpoint somente depois de confirmação de `PutEvents`.
3. Criar, se exigido na defesa, roteamento SNS→SQS de notificações de negócio, com policy de tópico/fila, DLQ e consumidor idempotente.
4. Tratar `FailedEntryCount`, limite regional/da conta, retry/backoff, duplicidade, DLQ e replay durável.
5. Solicitar e validar **quotas EventBridge compatíveis com picos de 5.000 TPS** e calcular o custo do fan-out; nenhuma quota foi alterada no código.
6. Não introduzir *dual write* na requisição síncrona de autorização.

**Alternativa B — Kinesis como caminho transacional e EventBridge só para integração:**

Manter o desenho atual por throughput/custo e defender a exceção com análise de quotas, latência, durabilidade e trade-offs. É arquiteturalmente plausível, porém **não cumpre literalmente a indicação de TransacaoAutorizada via EventBridge** do item 2.4.

## Decisão nesta auditoria

**Preservar Kinesis existente** até decisão explícita, evitando inserir uma ponte não dimensionada no caminho de 5.000 TPS. O gap permanece **ABERTO**; a alternativa A é a recomendação caso a avaliação exija correspondência literal.

## Regras inegociáveis

- `PutRecords` do Kinesis **não assegura ordenação**; o código/documentação já reconhece a limitação.
- Consumer idempotente por `event_id`, não por posição ou ordenação.
- Monitorar retenção, `IteratorAge`, DLQ/quarentena e recuperação de Outbox.
- Nunca transformar o evento de domínio em confirmação financeira síncrona; o commit de autorização deve preceder os eventos.

## Evidências internas

- `funcoes/outbox-relay/.../autorizacoes/KinesisDestino.java`.
- `infra/terraform/modulos/eventos/main.tf`.
- `docs/adr/README.md` (ADR-028).
- `contracts/asyncapi/financas-eventos.yaml`.

**Estado:** ADR documentada, ponte **não implementada**; execução de teste de integração Kinesis→EventBridge depende de definição de escopo e benchmark.
