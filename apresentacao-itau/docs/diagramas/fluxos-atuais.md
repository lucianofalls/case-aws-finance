# Diagramas complementares — arquitetura revisada em 22/10/2026

> Os arquivos PNG/SVG entregues no ZIP original continuam preservados como referência visual,
> mas **não incluem** as correções C01/C03/C05/C06/C04 optional da auditoria.
> Os diagramas abaixo refletem a **topologia proposta no código**; não evidenciam execução AWS.

## 1. Fluxo de autorização e compensação

```mermaid
sequenceDiagram
  autonumber
  participant Cliente
  participant Auth as Autorizacao ECS
  participant Lim as Limites ECS
  participant D as DynamoDB Limites
  participant Stream as DynamoDB Streams
  participant Lambda as Agendador de verificacao
  participant Q as SQS verificacao atrasada
  participant Rec as Reconciliador
  Cliente->>Auth: POST /v1/.../autorizacoes + UUID
  Auth->>Lim: Executar operacao idempotente
  Lim->>D: TransactWriteItem POSICAO+OPERACAO
  D-->>Lim: Commit
  D-->>Stream: INSERT OPERACAO
  Stream->>Lambda: Registro imutavel
  Lambda->>Q: Mensagem 15 min depois
  Lim-->>Auth: Resultado
  Auth->>Auth: Persistir Autorizacao/Outbox
  Auth-->>Cliente: 201 / 200
  Q->>Rec: Verificar estado
  alt Autorizacao existente
    Rec-->>Q: ACK sem reversao
  else Autorizacao ausente
    Rec->>Lim: Reverter operacao orfa
    Lim->>D: Reversao idempotente
  end
```

O agendamento SQS e a verificação são **at-least-once**. Se Streams/Lambda/SQS falharem,
a conciliação diária tenta detectar lacunas. O prazo 15 min **não é SLA garantido**.

## 2. Eventos e topologia de fan-out (opcional)

```mermaid
flowchart LR
  A[Autorizacao] --> DD[(DynamoDB com Outbox)]
  DD --> ST[DynamoDB Streams] --> R[Lambda Outbox relay]
  R --> K[Kinesis Data Streams]
  K --> C[Contabilizacao / KCL / Aurora]
  K --> F[Firehose / S3 - trilha]
  K --> P[EventBridge Pipes - opcional, OFF]
  P --> B[EventBridge Bus]
  B --> S[SNS topico negocio]
  S --> Q[SQS notificacoes]
  P -. falha .-> PD[SQS DLQ Pipe]
  B -. falha .-> ED[SQS DLQ EventBridge]
  S -. falha .-> SD[SQS DLQ SNS]
  Q -. falha .-> QD[SQS DLQ consumidor]
```

A ponte Pipes/EventBridge e SNS/SQS de notificações foi **implementada em Terraform com flag OFF**.
Habilitar somente após revisar limites, IAM/KMS, transformação CloudEvents e custo incremental.

## 3. Ciclo AmountBlock e recuperação

```mermaid
stateDiagram-v2
  [*] --> RESERVADA: RESERVA
  RESERVADA --> PARCIAL: CAPTURA ou LIBERACAO parcial
  PARCIAL --> PARCIAL: outros movimentos parciais
  PARCIAL --> ENCERRADA: bloqueio remanescente = 0
  RESERVADA --> ENCERRADA: LIBERACAO integral / expiracao
  RESERVADA --> PARCIAL: CAPTURA parcial
  ENCERRADA --> [*]
```

A expiração depende do EventBridge Scheduler em AWS e duas DLQs diferentes (entrega Scheduler e processamento SQS).
No Compose local a entrega é **simulada diretamente por SQS**, não valida o Scheduler gerenciado.

O republicador normal funciona enquanto o item ainda existe no DynamoDB; após TTL,
o runbook de **replay supervisionado da trilha S3** é obrigatório. Não assumir recuperação automática do evento expirado.
