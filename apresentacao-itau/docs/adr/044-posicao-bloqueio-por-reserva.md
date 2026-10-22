# ADR-044 — Invariante de valor bloqueado POR RESERVA no DynamoDB

**Data:** 2026-10-22. **Status:** implementado no adaptador DynamoDB; 9 testes de integração DynamoDB Local registrados na evidência da Fase 1; E2E pendente.

## Contexto
O saldo bloqueado agregado do contrato não permite distinguir CAPTURA/LIBERACAO
concorrentes de duas reservas. A validação no agregado Autorização ocorre em um
microserviço e commit diferente do Limites, podendo ficar obsoleta.

## Decisão
- Ao confirmar RESERVA, gravar item `RESERVA#<id_operacao>` com saldo `restante`
  e estado `ATIVA` **na mesma TransactWriteItems** da atualização de `POSICAO`
  e criação de `OPERACAO#` (idempotência).
- CAPTURA/LIBERACAO fazem `UPDATE` condicional atômico: `restante >= valor`
  e estado ATIVA, mesmo contrato, moeda correspondente.
- A operação de reversão atualiza os mesmos itens em uma TransactWriteItems;
  não desloca bloqueios entre reservas.
- `versao` da POSICAO protege o agregado do contrato (serialização otimista).
- Falha de condição da reserva vira violação de regra de negócio; nunca
  transformar condição inválida em sucesso silencioso.

## Consequências
+ Impede gastar uma reserva contra o bloqueio de outra.
+ Não exige lock distribuído e mantém BIAN Position Keeping independente.
− Aumento de 1 item por RESERVA e atualizações por CAPTURA/LIBERACAO;
  rever preço WCU e TTL próprios no estudo FinOps.
− Continua existindo mini-saga Autorização↔Limites; expiração, concorrência,
  queda depois de commit, replay e reversão precisam de teste com DynamoDB Local/Testcontainers.

**Refs:** `DynamoDbPosicaoRepository.java`, `ItensDeLimites.java`, `AplicarOperacaoService.java`.
