# ADR-039 — Entrega de expiração e quarentena financeira sem descarte silencioso

**Data:** 2026-10-22  
**Status:** Implementada em código/IaC; validação Maven, Terraform e E2E pendente.

## Problema

1. O EventBridge Scheduler entrega a fila de expiração com seu próprio mecanismo de retry; a DLQ de consumo da SQS não cobre falha de envio **antes de entrar na fila**.
2. O tradutor contábil usava `default -> Optional.empty()` para qualquer CloudEvent desconhecido. Com reentrega de Kinesis e na quarentena, um tipo novo/incompatível poderia ser confirmado e descartado silenciosamente.

## Decisão

- Scheduler recebe `RetryPolicy` com janela de até 24h e até 185 retentativas e uma **DLQ específica** para falhas do próprio Scheduler. É diferente de `reservas-a-expirar-dlq`, usada após a entrega do evento ao consumer. O IAM role do Scheduler recebe `sqs:SendMessage` nas duas filas.
- A DLQ do Scheduler integra o mapa de alarmes CloudWatch SQS do ambiente prod; mensagens exigem triagem e replay operacional, não são ignoradas.
- O payload da expiração é serializado em JSON por Jackson para não invalidar a mensagem com caracteres especiais.
- Apenas `TransacaoRecusada`, conhecida e sem efeito contábil, pode ser ignorada explicitamente pelo tradutor. Evento desconhecido gera erro, segue retry e quarentena até investigação. Rejeição de schema inválido também permanece não reconhecida.

## Alternativas

- `RetryPolicy` sem DLQ foi rejeitado: perda de entrega ao target pode ficar sem evidência durável.
- Descartar eventos desconhecidos foi rejeitado: incompatível com evolução de schema e trilha de auditoria financeira.
- Reprocessar tudo automaticamente com sucesso forçado foi rejeitado: mascara incompatibilidade e pode violar lançamentos.

## Consequências

- Duas DLQs com responsabilidades distintas para expiração: **Scheduler delivery** e **consumer processing**.
- Consumidores de Kinesis e SQS permanecem *at-least-once*, com Inbox idempotente.
- Falhas podem chegar à DLQ e demandar intervenção ou redrive; a entrega não é `exactly-once` nem garantia temporal absoluta.
- `ExpirarReservaService` permanece com a semântica atual, sem alterar regras financeiras já aprovadas pela implementação.

## Evidência e próximos testes

- Teste unitário novo para eventos desconhecidos que **não** são deletados da quarentena.
- Teste atualizado de construção de JSON da expiração.
- Executar `mvn verify` e `terraform validate` **localmente** no Mac antes de afirmar conformidade de runtime.
- Injetar falha do target Scheduler e confirmar a DLQ específica; depois falha no consumer e confirmar a outra DLQ.
