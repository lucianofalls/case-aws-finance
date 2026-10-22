# ADR-040 — Publicação opcional de TransacaoAutorizada v1 no EventBridge via Kinesis Pipes

**Data:** 2026-10-22  
**Status:** Terraform implementado com feature flag **OFF por padrão**; validação de provider, quotas e custos pendente.

## Motivação

O PDF POD99 §2.4 traz `TransacaoAutorizada` v1 no **Amazon EventBridge**, mas o desenho do Claude mantém eventos financeiros de grande volume em **Kinesis**. §3.3 pede EventBridge, SNS e SQS, além de comparar Kinesis/MSK/SQS. A entrega original tem rotas EventBridge para limites e SNS de alarmes, não rota de negócio `TransacaoAutorizada`.

## Decisão

1. **Preservar Kinesis como fonte principal** dos eventos de autorização, inclusive o consumidor contábil KCL. Não introduzir dual-write no request HTTP.
2. Adicionar `aws_pipes_pipe.transacoes_para_eventbridge` (Kinesis JSON CloudEvent → EventBridge). O Pipe filtra o `type` exato de `TransacaoAutorizada` v1 e envia à custom bus com `source=itau.financas.autorizacao`, `detail-type=TransacaoAutorizada.v1`, `detail.event` preservando o CloudEvent completo (incluindo seu `event_id`).
3. Regras EventBridge publicam no tópico **SNS de notificações de negócio**, que entrega a uma **SQS** com fila de erros própria. Também há DLQs para Pipe, EventBridge e entrega SNS, com alarmes no módulo de observabilidade.
4. **Feature flag `habilitar_eventbridge_transacoes` OFF por padrão**, inclusive produção. Ativar só com avaliação de quotas `PutEvents`/invocações, número de leitores Kinesis por shard, IAM/KMS, p95/p99 e custo de Pipes/Bus/SNS/SQS.
5. Esta topologia não modifica a contabilização atual e não constitui um serviço de notificações ao cliente; a SQS de integração aguarda consumidor real e processamento idempotente.

## Por que é opcional

Publicar 5.000 eventos/s pelo EventBridge **pode exceder quotas regionais padrão**; habilitar sem dimensionamento seria uma falha de confiabilidade. A flag permite demonstrar o compromisso com o contrato do case sem afirmar capacidade não comprovada.

## Consistência e replay

- AWS Pipes usa leitura compartilhada do Kinesis, que tem concorrência e banda limitadas por shard; o restante dos consumidores já compete pelo stream. Medir antes de ligar.
- Filtrar `data.type` em Kinesis com EventBridge Pipes exige CloudEvents JSON válidos: registros inválidos não são garantidos no destino filtrado. Alertar e auditar o produtor, não tratar Pipe como arquivo legal de tudo.
- `InputTemplate = {"event": <$.data>}` mantém JSON válido ao contrário de interpolar diretamente `<$.data>` para um objeto JSON.
- SNS/SQS/EventBridge permanecem *at-least-once*, sem exactly-once nem ordenação global. Inbox pelo `event_id`.
- Pipe com falha deve ser acompanhado na DLQ e reprocessado, e o stream mantém retenção finita. Reconciliador da fonte continua obrigatório.

## Critérios de aceite antes de habilitar

1. `terraform init/validate/plan` com flag em `false` e `true` (somente plano, não aplicar AWS sem autorização).
2. Enviar CloudEvent e observar `detail.event.type`, `detail.event.id` e o SNS→SQS com uma única identidade de negócio.
3. Testar falha de target e redrive de Pipe/SNS/SQS + alarmes, duplicidade e out-of-order.
4. Comprovar quotas reais e throughput/custo no cenário de pico 5.000 TPS e capacidade extra de leitores Kinesis.
5. Reconciliar a divergência anterior da ADR-037: implementação preparada, **ainda não ativada nem validada**.

## Referências

- https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-kinesis.html
- https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-event-filtering.html
- https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-pipes-input-transformation.html
- PDF POD99 §2.4, §3.3.
