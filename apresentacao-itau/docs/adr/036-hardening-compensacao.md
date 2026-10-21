# ADR-036 — Defesa em profundidade na compensação e na conciliação

- **Data:** 2026-10-21
- **Status:** implementada no código para revisão; validação Maven/Docker/AWS pendente
- **Pilares Well-Architected:** Reliability, Security, Operational Excellence
- **Contexto de negócio:** operações financeiras com confirmação distribuída entre Autorização e Limites

## Contexto
A autorização é confirmada depois de uma chamada síncrona ao Limites. A operação do Limites pode ser confirmada e a decisão na Autorização não ser gravada. A arquitetura existente usa (a) a operação imutável com ID determinístico; (b) DynamoDB Streams + Lambda de agendamento; (c) SQS com atraso; e (d) reconciliação diária via Step Functions. Esses mecanismos **já estavam no ZIP importado** — não estão sendo substituídos.

## Decisões adicionais

1. **Falhar fechado perante respostas inesperadas:** o caso de uso `VerificarAutorizacaoIncerta` aceita somente `REVERTIDA`, `JA_REVERTIDA` ou `NAO_ENCONTRADA` do Limites. Qualquer outro `desfecho` provoca erro para a Lambda/SQS retentar e, se esgotado, enviar à DLQ. Nunca confirmar uma mensagem por retorno silencioso.
2. **Anti-join tolerante a NULL:** a consulta Athena de operações órfãs usa `NOT EXISTS` em vez de `NOT IN` para impedir que uma linha nula no conjunto da Autorização suprima todas as candidatas.
3. **JSON de compensação gerado por biblioteca:** a montagem do corpo enviado à SQS usa Jackson em vez de concatenação de strings, preservando IDs válidos que contenham aspas ou escapes.

## Consequências
- Falhas desconhecidas deixam de ser tratadas como sucesso.
- Reconciliador precisa suportar retry e DLQ (configuração existente, a validar em AWS).
- Menos risco de falso negativo por dados inesperados na consulta Athena.
- Nenhuma mudança nas fronteiras BIAN/DDD ou nas transições financeiras foi introduzida.

## Testes adicionados
- `VerificarAutorizacaoIncertaTest`: retorno inesperado falha, retorno `JA_REVERTIDA` é idempotente.
- `ConciliacaoTest`: consulta exige `NOT EXISTS` e não usa `NOT IN`.
- `SqsFilaDeVerificacaoTest`: payload continua JSON válido quando o contrato tem aspas.

## Limites
- A verificação de compilação Java pura e de sintaxe dos artefatos foi executada localmente; **`mvn test`, `terraform validate`, deploy e teste E2E não foram executados nesta revisão**.
- A separação entre Autorização e Limites continua uma mini-saga, não uma única transação ACID de dois serviços.
- O tempo de retenção do Stream é 24 h; a conciliação diária + republicador já existem como recuperação, mas precisam de testes de desastre.
- Nenhum GitHub Actions foi criado ou ativado.

## Referências internas
- [ADR-027 — id determinístico e verificação posterior](README.md#027--id-determinístico-sem-pendente-verificação-posterior-com-janelas-disjuntas)
- [ADR-028 — Kinesis vs EventBridge](README.md#028--kinesis-no-fluxo-transacional-eventbridge-na-integração)
- `infra/terraform/modulos/conciliacao/main.tf`
- `funcoes/reconciliador/src/main/java/br/com/itau/financas/reconciliador/`
