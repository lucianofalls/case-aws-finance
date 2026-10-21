# Memória do Projeto — Desafio Itaú POD99 (Arquiteto de Aplicação)

> Documento vivo: registra contexto, decisões, status e histórico do trabalho.
> **Atualizar ao final de cada sessão/etapa relevante.**
>
> **Princípio norteador:** toda decisão é organizada pelo **AWS Well-Architected Framework (WAF)**
> — 6 pilares + **Financial Services Industry Lens** + **Serverless Lens** — e prefere
> **serviços gerenciados AWS** a componentes construídos/operados por nós.
> Código próprio só onde há regra de negócio ou não existe equivalente gerenciado.

> **Entregável implementado:** [../apresentacao-itau/](../apresentacao-itau/README.md) — código (9 módulos Maven,
> 80 testes + 4 de integração), Terraform (prod + local), contratos (OpenAPI/AsyncAPI/JSON Schema), docker-compose, roteiro de demo,
> ADRs 026–030, custos (`docs/custos/calcular_custos.py`) e concorrência. A partir da v2, o entregável é a fonte de
> verdade da arquitetura; este documento guarda o histórico e as validações.

Legenda de status: **P** = proposta · **A** = aceita · **R** = rejeitada/substituída · ⚠️ = validar · ✅ = validado em fonte oficial

---

## 1. Contexto do desafio

- **Fonte:** `Arquiteto de Aplicação.docx.pdf` (raiz do workspace)
- **Vaga:** Arquiteto de Aplicação — POD99 AWS — Nova Plataforma de Finanças (Itaú Unibanco)
- **Caso:** Plataforma de **Gestão de Limites e Autorização de Transações**
- **Prazo:** 5 dias úteis (início: 2026-10-20)
- **Entrega:** ZIP com código, IaC, diagramas e ADRs
- **Defesa (60 min):** 20 min apresentação + 40 min perguntas + demo (docker local ou conta AWS)
- **IA:** permitida — mas é preciso explicar cada decisão e o código

### Volumetria / NFRs
| Métrica | Valor |
|---|---|
| Contas ativas | ~80 milhões |
| Contratos por conta | 3–5 (≈ 320M limites) |
| Throughput de autorização (pico) | ~5.000 TPS (≈ 15k escritas/s no banco) |
| Picos | previsíveis (início de mês, pagamentos) e imprevisíveis (campanhas, Black Friday) |
| Não especificado no PDF (assumir e declarar) | latência alvo p99 < 100 ms na borda; disponibilidade 99,95%; RPO ≈ 0 / RTO < 15 min |

### Contrato de API (base)
`POST /v1/contratos/{id_contrato}/autorizacoes` — header obrigatório `Idempotency-Key: <UUID>`

Request: `id_conta`, `valor` (decimal 18,2 > 0), `moeda` (ISO 4217), `tipo_operacao` (DEBITO | CREDITO | RESERVA | ESTORNO), `id_estabelecimento` (opc.), `metadata` (opc.)

| HTTP | Semântica | Quem produz |
|---|---|---|
| 201 | Aprovada — `id_autorizacao` + saldo reservado | serviço |
| 200 | Repetição idempotente — resultado original | serviço |
| 402 | Limite insuficiente | serviço (domínio) |
| 409 | Conflito de concorrência na reserva | serviço (conditional write) |
| 422 | Payload inválido | API Gateway (request validator OpenAPI) + serviço (regras de domínio) |
| 429 | Rate limit por cliente/plano | **API Gateway usage plan** (gerenciado) |
| 503 | Circuit breaker aberto / fail-fast | serviço (Resilience4j) |

Requisitos: idempotência em toda operação mutante, `/v1` com política de depreciação, erros RFC 7807, OpenAPI 3.x contract-first.

### Contrato de evento (base)
`TransacaoAutorizada` v1 — EventBridge — CloudEvents. Campos: `event_id`, `event_type`, `event_version`, `occurred_at`, `id_autorizacao`, `id_contrato`, `valor`, `saldo_reservado`, `correlation_id`, `trace_id` (nullable).
Regras: imutável, nome no passado, `event_id` único (at-least-once), evolução retrocompatível via schema registry, consumidores idempotentes e tolerantes a reordenação/reentrega.
Consumidores: contabilidade, antifraude, notificações, data platform.

### Requisitos técnicos a demonstrar
SOLID · Clean Code · Hexagonal · DDD (BCs, agregados, invariantes, linguagem ubíqua) · CQRS/Repository/Domain Events (usar ou não) · compute justificado · database-per-service · EventBridge+SNS+SQS · SQS vs Kinesis vs MSK · API Gateway + auth + usage plans · contract-first · Gateway vs Mesh vs BFF · **Well-Architected**.

---

## 2. Arquitetura de solução (v1 — AWS-first)

```
                         Clientes / Canais (M2M)
                                  │
       Route 53 ──► AWS WAF + Shield ──► Amazon API Gateway (REST, regional)
                                  │  • Cognito authorizer (OAuth2 client_credentials, scopes)
                                  │  • mTLS (parceiros) • usage plans/quotas (429)
                                  │  • request validator OpenAPI (422) • canary stage
                                  │  VPC Link → NLB (privado)
┌───────────────────────── VPC privada, 3 AZs ───────────────────────────────┐
│  ECS Fargate (Graviton/ARM64) — Auto Scaling target tracking + scheduled    │
│  ┌──────────────────────┐  ECS Service Connect   ┌──────────────────────┐   │
│  │ autorizacao-service  │ ─(timeout/retry/outlier)►│  limites-service    │   │
│  └─────────┬────────────┘                         └─────────┬────────────┘   │
│            ▼                                                ▼                │
│  DynamoDB `autorizacoes`                          DynamoDB `limites`         │
│  (AUT#, IDEMP# c/ TTL, OUT#)                      (conditional write, OUT#)  │
│  PITR · KMS CMK · deletion protection             PITR · KMS CMK             │
│            │ Streams                                        │ Streams        │
│  VPC endpoints: DynamoDB (gateway), EventBridge, SQS, ECR, Logs, KMS, STS    │
└────────────┼────────────────────────────────────────────────┼────────────────┘
             └──────────► EventBridge Pipes (filtro OUT#, enrichment=CloudEvent)
                                         ▼
             Amazon EventBridge — bus `financas` · Schema Registry · Archive & Replay
        ┌──────────────┬───────────────┬────────────────┬──────────────────┐
        ▼              ▼               ▼                ▼                  ▼
  SQS FIFO        SQS std         SNS (notif.)     Data Firehose      CloudWatch Logs
  (group=contrato) (antifraude)                    → S3 (Parquet)     (auditoria)
        ▼              ▼               ▼             + Glue Catalog
  Fargate (consumer)  Lambda stub   Lambda stub      + Athena / Lake Formation
  contabilizacao                                     S3 Object Lock (WORM) p/ trilha
        ▼                                            de auditoria regulatória
  Aurora PostgreSQL (Graviton, Multi-AZ, RDS Proxy, IAM auth, Secrets Manager)

  corebanking-acl: Lambda ◄── legado (mock) ──► publica LimiteConcedido/LimiteAlterado

  Transversal: CloudWatch Application Signals (SLOs) · X-Ray via ADOT · CloudTrail ·
  AWS Config · Security Hub · GuardDuty · Inspector (ECR) · AWS Backup ·
  Fault Injection Service · Resilience Hub · Budgets/Cost Explorer (tags) · WA Tool
```

---

## 3. Domínio — BIAN, bounded contexts e microsserviços

BIAN usado como **mapa de referência** (fronteiras e vocabulário), não 1:1 (service domain ≠ microsserviço).

### 3.1 Validação do BIAN (feita em 2026-10-20) ✅
**Fontes oficiais consultadas (públicas, sem conta AWS):**
- BIAN Service Landscape **v12.0** — matriz oficial (`bian.org/servicelandscape-12-0-0`, view "Matrix View"): 5 Business Areas, 39 Business Domains, 324 Service Domains
- BIAN Service Landscape **v13.0** — mesma matriz (`bian.org/servicelandscape-13-0-0`): 327 SDs. **Todos os SDs usados aqui estão no mesmo lugar na v12 e na v13.** A v13 só acrescentou Corporate Strategy, Customer Financial Insights e Service Provider Operations, que não afetam o case
- Definições oficiais (*Role Definition*, *Example of Use*, *Key Features*) e diagramas de **Control Record** de cada SD (mesmo portal)
- BIAN **Semantic APIs** oficiais (`github.com/bian-official/public`, release 12.0.0, OAS3), para as operações (Action Terms)

**Erros corrigidos em relação à versão anterior deste documento:**
| Antes (de memória) | Validado no BIAN oficial |
|---|---|
| Credit Facility em Loans & Deposits, como SD principal de Limites | Credit Facility fica em **Trade Banking** e é um acordo de crédito **corporativo** (guarda-chuva). **Não usamos** como SD principal |
| Position Keeping na Contabilização | Position Keeping **"maintain positions as configured (includes limits and blocks)"**, ou seja, **limite e bloqueio (reserva) por contrato são dele**. Vai para o BC **Limites** |
| Financial Accounting em Finance | Financial Accounting fica em **Risk and Compliance › Regulations and Compliance** |
| "Fraud Detection" | **Não existe** na v12/v13. O SD correto é **Fraud Evaluation** (Account Management) |
| Business Domain "Cross Product Operations" | É só um agrupamento visual da matriz. O Business Domain oficial do Position Keeping é **Account Management** |

### 3.2 Hierarquia validada (Business Area → Business Domain → Service Domain)
| Business Area | Business Domain | Service Domain | Uso no case |
|---|---|---|---|
| Sales and Service | Cross Channel | **Transaction Authorization** | ✅ núcleo |
| Sales and Service | Customer Management | **Sales Product Agreement**, Customer Agreement | 🔌 via ACL (o "contrato") |
| Sales and Service | Customer Management | **Customer Event History** | 📨 consumidor (data platform) |
| Operations and Execution | Account Management | **Position Keeping** | ✅ núcleo |
| Operations and Execution | Account Management | **Fraud Evaluation** | 📨 consumidor (antifraude) |
| Operations and Execution | Trade Banking | **Limit and Exposure Management** | ✅ núcleo (regra de limite/exposição) |
| Operations and Execution | Loans and Deposits | **Current Account**, **Consumer Loan** | 🔌 via ACL (legado) |
| Operations and Execution | Cards | **Credit Card** | 🔌 via ACL (legado) |
| Operations and Execution | Investment Management | **Investment Account** | 🔌 via ACL (legado) |
| Operations and Execution | Consumer Services | **Brokered Product** (seguros via corretora) | 🔌 via ACL (legado) |
| Risk and Compliance | Regulations and Compliance | **Financial Accounting** | ✅ núcleo |
| Business Support | Document Management and Archive | **Correspondence** | 📨 consumidor (notificações) |
| Business Support | Document Management and Archive | **Archive Services** | 📨 consumidor (trilha de auditoria) |
| Reference Data | Product Management | Product Directory | ⬜ considerado, não usado |

### 3.3 Cada SD, um a um: por que usamos (ligado ao PDF)

**Núcleo: implementado no código**

1. **Transaction Authorization** (Sales and Service › Cross Channel) → BC **Autorização**
   - *BIAN:* "authorizes transactions accordingly… consolidate details of proposed customer transaction". O Control Record oficial é **Interactive Transaction Assessment**, com os campos *Proposed Transaction Type*, *Proposed Transaction Value*, *Product Instance Reference* e *Transaction Authorization Result*. Operação semântica: **Evaluate** (`/TransactionAuthorization/Evaluate`)
   - *PDF:* "Autorização síncrona de transações com decisão de aprovação/recusa em tempo real", `POST /v1/contratos/{id}/autorizacoes`
   - *Mapeamento:* `tipo_operacao` = Proposed Transaction Type; `valor` = Proposed Transaction Value; `id_contrato` = Product Instance Reference; 201/402 = Transaction Authorization Result
   - *Trade-off:* no BIAN, o TA também analisa padrão/fraude **inline**. Nós deixamos a fraude **assíncrona** (Fraud Evaluation) para caber no orçamento de latência a 5k TPS. Um score inline pode entrar depois como chamada síncrona com timeout curto

2. **Position Keeping** (Operations and Execution › Account Management) → BC **Limites**
   - *BIAN:* "Maintain a log of financial transactions… maintain balances/positions… **Maintain positions as configured (includes limits and blocks)**… The financial transactions are subsequently posted to the accounting systems". O Control Record oficial é **Financial Position Log**, com *Product Instance Reference : Product Agreement*, *Position Limit Value*, *Managed Position Limits* e **Amount Block**. O Behavior Qualifier é **Financial Transaction Capture**. Operações: Initiate, Update, **Control**, Retrieve, **Capture**
   - *PDF:* "Verificação e **reserva** de limite por contrato, com garantia de consistência sob concorrência"
   - *Mapeamento:* agregado `LimiteContrato` = Financial Position Log; `Reserva` = Amount Block; `Movimento` (débito, crédito, reserva, estorno) = Financial Transaction Capture; `id_contrato` = Product Instance Reference
   - *Por que em Limites, e não na Contabilização:* o próprio BIAN coloca limite e bloqueio dentro do Position Keeping, e é isso que o caminho síncrono precisa consultar e alterar atomicamente (conditional write no DynamoDB)

3. **Limit and Exposure Management** (Operations and Execution › Trade Banking) → BC **Limites** (política)
   - *BIAN:* "Oversee corporate limits and exposures for the combined business activity… **Product fulfillment activity triggers limit and exposure checks**"
   - *PDF:* "gerenciar limites de crédito por contrato", "gestão de risco"
   - *Uso:* é a **política** de limite (limite concedido por contrato, futura visão de exposição consolidada por cliente). O Position Keeping cuida da **execução** (saldo disponível, bloqueios). Os dois ficam no mesmo BC porque compartilham a invariante `disponivel >= 0`
   - *Ressalva:* o BIAN descreve o SD com foco corporativo. No varejo usamos a capacidade de checagem, não a governança corporativa

4. **Financial Accounting** (Risk and Compliance › Regulations and Compliance) → BC **Contabilização**
   - *BIAN:* "takes in financial facts and… creates accounting instructions… general ledger and sub ledger… **retrieve all of today's bookings on Position Keeping**". O Control Record oficial é **Financial Booking Log**, e o Behavior Qualifier é **Ledger Posting** (*Posting Direction*, *Posting Amount (credit/debit pair)*). Operações: Initiate e **Capture LedgerPosting**
   - *PDF:* "publicar eventos contábeis para os sistemas de retaguarda", consumidor "contabilidade"
   - *Mapeamento:* o evento `TransacaoAutorizada`/`EstornoRealizado` é o "financial fact"; `Lancamento` (débito/crédito) = Ledger Posting; Aurora = razão (sub ledger, GL)
   - *Observação:* o BIAN classifica o SD em Risk and Compliance, não em Finance. Isso reforça o requisito de auditoria e rastreabilidade do PDF

**Integração: o legado de core banking, atrás da ACL (mock na demo)**

5. **Sales Product Agreement** (Sales and Service › Customer Management): é o **"contrato"** do PDF. *BIAN:* "legal terms and conditions in force for a sold product… subordinate to the customer master agreement (Customer Agreement)… defining applicable fees/rates/penalties". A ACL lê daqui o **limite concedido** do contrato e publica `LimiteConcedido`/`LimiteAlterado`
6. **Current Account** (Loans and Deposits): o `id_conta` do PDF ("Número da conta corrente"). *BIAN:* "general checking account facility at the core of consumer and small business banking"
7. **Consumer Loan** (Loans and Deposits) e **Credit Card** (Cards): contratos de **crédito** do PDF
8. **Investment Account** (Investment Management): contratos de **investimento** do PDF
9. **Brokered Product** (Consumer Services): **seguros**. *BIAN:* "access to a bank approved insurance broker"
10. **Consórcio: não existe SD no BIAN.** É uma lacuna documentada, porque o produto é típico do Brasil. Tratamos como produto do legado atrás da ACL, e é um bom ponto para a defesa

**Consumidores dos eventos (stubs ou serviços gerenciados)**

11. **Fraud Evaluation** (Account Management) → stub antifraude. *BIAN:* "fraud detection tests… may run in-line with production or **may access transaction logs**". Como consumidor do evento, é o modo "transaction logs"
12. **Correspondence** (Document Management and Archive) → stub de notificações (SNS). *BIAN:* "message delivery channels (snail mail, email, etc)". *Ressalva:* o SD tem foco em correspondência em lote. O encaixe é parcial, mas é o SD mais próximo
13. **Customer Event History** (Customer Management) → data platform (Firehose → S3). *BIAN:* "Log product fulfillment events and alerts… for behavioral analysis"
14. **Archive Services** (Document Management and Archive) → trilha de auditoria (S3 Object Lock). *BIAN:* "maintained for legal/regulatory reasons", o que atende ao requisito de auditoria do PDF

### 3.4 Considerados e descartados (com motivo)
| SD | Onde fica | Por que não |
|---|---|---|
| Card Authorization | Cards | Específico de cartão (adquirente, bandeira). O PDF é multiproduto (crédito, investimento, seguros, consórcio) |
| Credit Facility | Trade Banking | Acordo de crédito **corporativo** guarda-chuva, enquanto o PDF trata limite por contrato no varejo. *Futuro:* hierarquia de limites (limite global do cliente com sublimites) |
| Credit Management | Trade Banking | Política e precificação de crédito do banco inteiro, que fica antes da plataforma (upstream) |
| Customer Credit Rating | Customer Management | Score que informa a **concessão** do limite, também upstream. A plataforma só consome o limite já concedido |
| Customer Access Entitlement | Customer Management | Limite de **uso por canal** (ex.: saque diário no ATM). É outro conceito de "limite", e não deve ser confundido com o 429 (throttling de API) |
| Customer Position | Account Management | Visão consolidada de todos os produtos do cliente. Candidato a **read model CQRS** no futuro |
| Payment Execution | Payments | Movimenta dinheiro. Nós **autorizamos e reservamos**, não liquidamos |
| Account Reconciliation | Account Management | Conciliação nostro/vostro. A conciliação diária Position Keeping × Financial Accounting é opcional |
| Regulatory Reporting | Regulations and Compliance | Fica a jusante (downstream), alimentado pela data platform |
| Party Authentication | Cross Channel | O canal já autenticou o cliente. Nosso OAuth2 é autenticação máquina a máquina |
| Product Directory | Product Management | Regras por tipo de produto (quais `tipo_operacao` valem). Opcional, como dado de referência em cache |

### 3.5 Resultado: SDs → Bounded Contexts → microsserviços
| Bounded context | SDs BIAN | Microsserviço | Subdomínio | Compute / dados |
|---|---|---|---|---|
| Autorização | Transaction Authorization | `autorizacao-service` | Core | Fargate / DynamoDB |
| Limites | Position Keeping + Limit and Exposure Management | `limites-service` | Core | Fargate / DynamoDB |
| Contabilização | Financial Accounting | `contabilizacao-service` | Supporting | **Fargate** (consumidor SQS) / Aurora PG |
| Core Banking ACL | Sales Product Agreement, Current Account, Consumer Loan, Credit Card, Investment Account, Brokered Product (+ consórcio, sem SD) | `corebanking-acl` | Integração | Lambda / mock legado |
| (stub) Antifraude | Fraud Evaluation | consumidor | fora do escopo | Lambda |
| (stub) Notificações | Correspondence | consumidor | fora do escopo | Lambda (SNS) |
| (gerenciado) Data platform | Customer Event History | Firehose → S3 | fora do escopo | gerenciado |
| (gerenciado) Auditoria | Archive Services | Firehose → S3 Object Lock | fora do escopo | gerenciado |

**Total: 4 microsserviços + 2 stubs + 2 fluxos gerenciados (sem código).**

### 3.6 Vocabulário: BIAN → linguagem ubíqua (PT-BR)
| BIAN (oficial) | Tipo | Nosso modelo |
|---|---|---|
| Interactive Transaction Assessment | Control Record (TA) | agregado `Autorizacao` (PENDENTE → APROVADA/RECUSADA → ESTORNADA) |
| Evaluate | Action Term (TA) | caso de uso `AutorizarTransacao` |
| Financial Position Log | Control Record (PK) | agregado `LimiteContrato` (invariante `disponivel >= 0`) |
| Amount Block | atributo do CR (PK) | entidade `Reserva` |
| Financial Transaction Capture | Behavior Qualifier (PK) | entidade `Movimento` |
| Control / Capture | Action Terms (PK) | casos de uso `ReservarLimite`, `LiberarReserva`, `RegistrarMovimento` |
| Financial Booking Log | Control Record (FA) | agregado `RegistroContabil` |
| Ledger Posting | Behavior Qualifier (FA) | entidade `Lancamento` (par débito/crédito) |
| Sales Product Agreement | SD (via ACL) | VO `ContratoId` + evento `LimiteConcedido` |

O nome do agregado fica em português e segue o PDF ("Limite", "Autorização"), porque **a linguagem ubíqua é a do negócio**. O BIAN entra como glossário de equivalência.

📐 Torre LEGO BIAN → DDD (validada): [diagramas/piramide-bian-ddd.svg](diagramas/piramide-bian-ddd.svg). É gerada por `python docs/diagramas/gerar_piramide_bian_ddd.py`; para mudar, edite `LAYERS` no script.

### Context map
- Autorização → Limites: **Customer/Supplier**, síncrono (ECS Service Connect), OpenAPI interno
- Autorização / Limites → consumidores: **Published Language** (CloudEvents)
- Core Banking → Limites: **ACL**

### Consistência Autorização ↔ Limites (mini-saga)
Database-per-service ⇒ sem transação única.
1. Autorização valida, checa idempotência, grava `Autorizacao` = PENDENTE
2. Chama Limites `POST /reservas` idempotente por `id_autorizacao` (402 insuficiente / 409 conflito)
3. Grava APROVADA/RECUSADA + outbox na mesma `TransactWriteItems`
4. Falha entre 2 e 3 → **reconciliador** (EventBridge Scheduler → Lambda) confirma ou compensa PENDENTES expiradas
Alternativa: monólito modular Autorização+Limites (1 transação, menor latência, maior acoplamento).

### Padrões táticos
- **Repository:** sim — port no domínio, adapter DynamoDB/Aurora
- **Domain Events:** sim — gerados pelo agregado, publicados via outbox
- **CQRS:** não no caminho de escrita; sim para leitura analítica/extrato (eventos → S3 + Athena, ou read model dedicado)

### 3.7 Concorrência: como o mesmo dado não é atualizado duas vezes (2026-10-20)

**Princípios**
- **Sem lock distribuído** (Redis/ZooKeeper): mais uma peça para operar, com risco de lock órfão e latência extra. A serialização acontece **no próprio item do banco**
- **Escrita condicional em vez de ler, alterar e gravar:** a regra vai **dentro** da escrita (`ConditionExpression`), então nunca há *lost update*
- **Toda operação mutante é idempotente:** retry, reentrega e clique duplo não duplicam efeito
- **Máquina de estados com guarda:** toda transição exige o estado esperado (`estado = :esperado`)
- **Append-only onde possível:** movimentos, lançamentos e eventos não são atualizados, só inseridos. Sem disputa

**Catálogo de pontos de concorrência**
| # | Onde | Corrida (o que pode dar errado) | Mecanismo | Resultado |
|---|---|---|---|---|
| C1 | `limites` · item `POSICAO` | N autorizações paralelas no mesmo contrato gastam além do limite (*lost update*) | `UpdateItem` atômico: `SET disponivel = disponivel - :v` **com** `ConditionExpression disponivel >= :v`, dentro de `TransactWriteItems` (POSICAO + RESERVA + MOV + OUT) | condição falha → **402**; nunca fica abaixo de zero |
| C2 | `limites` · mesma transação | duas transações tocam o mesmo item ao mesmo tempo → DynamoDB cancela uma com `TransactionConflict` | retry na aplicação (2 a 3 vezes, backoff com jitter, dentro do orçamento de ~50 ms); esgotou → erro de conflito | **409** (a semântica do PDF: "concorrência na reserva") |
| C3 | `limites` · item `RESERVA#<id_autorizacao>` | a autorização reenvia a reserva (timeout ou retry) e reserva duas vezes | `attribute_not_exists(SK)` no item RESERVA dentro da transação; se já existe, devolve a reserva original | idempotente; nunca há reserva dupla |
| C4 | `autorizacoes` · item `IDEMP#<key>` | duas requisições com a mesma `Idempotency-Key` chegam juntas | `PutItem` com `attribute_not_exists(PK)` e estado EM_PROCESSAMENTO; quem chega depois lê: em processamento → **409**; concluída → **200** com a resposta salva; hash do payload diferente → **422** | só uma execução por chave |
| C5 | `autorizacoes` · item `AUT#<id>` | o fluxo normal aprova enquanto o reconciliador tenta compensar a mesma PENDENTE | `UpdateItem` com `ConditionExpression estado = PENDENTE`; quem chega em segundo recebe `ConditionalCheckFailed` e não faz nada | uma só transição vence |
| C6 | `autorizacoes` · estorno | dois estornos simultâneos (ou parciais) passam do valor aprovado | `SET valor_estornado = valor_estornado + :v` com `ConditionExpression estado = APROVADA AND valor_estornado + :v <= valor` (+ Idempotency-Key) | **422** se exceder; nunca estorna duas vezes |
| C7 | `limites` · `POSICAO` × evento `LimiteAlterado` do legado | o legado altera o limite enquanto há reservas; eventos do legado chegam fora de ordem | atualização por **delta** (`disponivel = disponivel + (novo - antigo)`) com `ConditionExpression versao_origem < :nova` (versão monotônica vinda da ACL); evento antigo é ignorado | não perde reservas em andamento e não aplica limite velho |
| C8 | `contabilizacao` · Aurora | o mesmo evento chega duas vezes (at-least-once) ou dois consumidores pegam mensagens do mesmo contrato | **Inbox:** `INSERT INTO evento_processado(event_id) ON CONFLICT DO NOTHING` na **mesma transação** dos lançamentos; SQS FIFO com `MessageGroupId = id_contrato` garante 1 consumidor por contrato por vez | lançamento único e em ordem por contrato |
| C9 | `contabilizacao` · saldos | atualizar saldo contábil numa linha quente (disputa no `UPDATE`) | **não manter saldo corrente em linha quente**: razão append-only; saldo calculado ou materializado em lote (fechamento) | sem disputa de linha |
| C10 | consumidores com fila padrão (antifraude, notificações) | duplicidade e reordenação | dedup por `event_id`; ignorar evento com `occurred_at`/versão mais antiga que o estado conhecido | tolerância exigida pelo PDF §2.4 |
| C11 | reconciliador | duas execuções simultâneas | EventBridge Scheduler + reserved concurrency = 1; e mesmo assim todas as ações são condicionais (C5) | seguro mesmo se rodar duas vezes |

**Por que não versão otimista (`versao = :v`) no débito?** Com contenção, a checagem de versão provoca conflitos em cascata (todo mundo leu a mesma versão). A condição sobre o **valor** (`disponivel >= :v`) com update atômico resolve o débito sem depender da leitura anterior. A versão continua útil para alterações administrativas do limite (C7) e para ETag/If-Match em uma futura API de manutenção (**412** Precondition Failed).

**Limites físicos e contrato "quente"**
- O DynamoDB serializa escritas no mesmo item; uma partição suporta até ~1.000 WCU/s. Um único contrato não chega perto disso no varejo
- Monitorar com **Contributor Insights** (top chaves). Se surgir contrato quente (ex.: corporativo), a evolução é **sub-limites fragmentados** (N buckets do mesmo contrato), com trade-off de complexidade
- Regra de negócio: a invariante `disponivel >= 0` vale para **novas reservas**. Uma redução de limite pelo legado pode deixar o disponível negativo, bloqueando novas autorizações sem desfazer as já aprovadas (registrar no ADR 021)

**Como vamos provar (testes e demo)**
- Teste de integração (Testcontainers + LocalStack): 50 threads no mesmo contrato → Σ aprovado ≤ limite; nenhuma reserva duplicada; respostas apenas 201/402/409
- Teste da mesma `Idempotency-Key` em paralelo → 1 execução, demais 409/200
- Teste de corrida aprovação × reconciliador → uma única transição
- Teste de reentrega do mesmo evento para a contabilização → 1 lançamento
- k6: cenário de contenção (muitos TPS em poucos contratos)
- Demo, passo 5 do roteiro

### 3.8 EDA: onde vai ter evento e o que vamos implementar (2026-10-20)

**Regra para decidir síncrono × evento**
- **Síncrono** quando o chamador **precisa da resposta para responder** ao cliente: autorização → limites (reserva). É o único acoplamento síncrono entre serviços
- **Evento** quando é um **fato consumado** ao qual outros reagem no seu tempo: tudo o mais

**Catálogo de eventos** (CloudEvents 1.0, bus `financas`)
| Evento | Produtor | Gatilho | Consumidores (destino) | Ordem necessária? | Implementar? |
|---|---|---|---|---|---|
| `TransacaoAutorizada` | autorização (outbox) | aprovação | contabilização (SQS FIFO), antifraude (SQS), notificações (SNS), data platform (Firehose → S3), auditoria (Firehose → S3 Object Lock) | por contrato (contabilidade) | ✅ produtor + todas as rotas |
| `TransacaoRecusada` | autorização (outbox) | 402 | antifraude, data platform, auditoria | não | ✅ |
| `EstornoRealizado` | autorização (outbox) | estorno | contabilização (FIFO), notificações, data platform, auditoria | por contrato | ✅ |
| `LimiteReservado` | limites (outbox) | reserva | data platform, auditoria | não | ✅ |
| `ReservaLiberada` | limites (outbox) | estorno/expiração/compensação | data platform, auditoria | não | ✅ |
| `LimiteAlterado` | limites (outbox) | aplicação de novo limite | data platform, auditoria | não | ✅ |
| `LimiteConcedido` / `LimiteAtualizadoNoLegado` | corebanking-acl | mudança no legado (mock) | **limites-service** (SQS → listener) | por contrato (versão monotônica, C7) | ✅ |
| `LancamentoContabilRegistrado` | contabilização | lançamento gravado | data platform, auditoria | não | ✅ (só publicar) |
| tick do reconciliador | EventBridge Scheduler | a cada 1 min | Lambda reconciliador | — | ✅ |

**Infra de eventos a implementar**
- Outbox nos dois serviços Fargate (item `OUT#` na mesma `TransactWriteItems`) → DynamoDB Streams → **EventBridge Pipes** (filtro `OUT#`, input transformer) → bus `financas`
- Regras por `source` + `detail-type` → alvos: SQS FIFO (contabilização), SQS (antifraude), SNS (notificações), SQS (limites, para eventos da ACL), Firehose (data platform e auditoria)
- **DLQ em todos os alvos** + alarme de profundidade; redrive documentado
- **Archive & Replay** do bus; **Glue Schema Registry** (FULL; era BACKWARD, corrigido em 2026-10-21) validado no CI
- Contabilização na **mesma transação** do Aurora: inbox (C8) + lançamentos
- AsyncAPI documentando canais, eventos e consumidores

**Fora do escopo (stub ou só configuração)**
- Antifraude e notificações reais: **stubs Lambda** que só registram e validam o contrato do evento
- Data platform (analytics) e Regulatory Reporting: só o Firehose → S3 (Parquet), sem pipeline analítico
- Kinesis/MSK: não usar agora (ADR da Fase 7.7)

---

## 4. Arquitetura por pilar do Well-Architected

### 4.0 Validação do Well-Architected (2026-10-20) ✅
Validado na **documentação pública da AWS** (docs.aws.amazon.com), sem uso da conta AWS:

| Afirmação usada na arquitetura | Resultado | Fonte |
|---|---|---|
| WA tem 6 pilares (Op. Excellence, Security, Reliability, Performance Efficiency, Cost Optimization, Sustainability) | ✅ confirmado | wellarchitected/latest/framework/the-pillars-of-the-framework |
| Existem as lentes **Financial Services Industry Lens** e **Serverless Applications Lens** | ✅ confirmado | wellarchitected/latest/financial-services-industry-lens, serverless-applications-lens |
| API Gateway **REST** (e não HTTP API) para usage plans, API keys, throttling por cliente, request validation, WAF, canary | ✅ confirmado: HTTP API **não** tem API keys, rate limit por cliente, request validation, WAF nem canary | apigateway/latest/developerguide/http-api-vs-rest |
| DynamoDB Streams como fonte do **EventBridge Pipes** (outbox) | ✅ confirmado | eventbridge/latest/userguide/eb-pipes-dynamodb |
| EventBridge **Archive & Replay** | ✅ confirmado | eventbridge/latest/userguide/eb-archive |
| DynamoDB **warm throughput** (pré-aquecer para Black Friday) | ✅ confirmado: "planned peak events… 10x, 100x, or more… pre-warming a table" | amazondynamodb/latest/developerguide/warm-throughput |
| **App Mesh** descontinuado | ✅ confirmado: **fim do suporte em 30/09/2026**, ou seja, já descontinuado na data de hoje. Não usar | app-mesh/latest/userguide/what-is-app-mesh |
| ECS **blue/green nativo** | ✅ confirmado (seção "Amazon ECS blue/green deployments" no ECS Developer Guide) | AmazonECS/latest/developerguide/deployment-type-blue-green |
| ECS **Service Connect** com retry/timeout/outlier detection | ✅ confirmado (outlier detection documentado) | AmazonECS/latest/developerguide/service-connect |
| Lambda **SnapStart** para Java | ✅ confirmado: Java 11 e posteriores | lambda/latest/dg/snapstart |
| DynamoDB **Global Tables MRSC** para DR de limites | ❌ **descartado**: exige exatamente 3 regiões (ou 2 + witness), aumenta a latência de escrita e **não está disponível em São Paulo (sa-east-1)** | amazondynamodb/latest/developerguide/multi-region-strong-consistency-gt |

### 4.1 Excelência Operacional
| Prática WA | Como atendemos | Serviço AWS |
|---|---|---|
| Operações como código | 100% IaC, ambientes reproduzíveis, drift detection, rollback automático | **AWS CDK (Java) → CloudFormation** + cdk-nag (AwsSolutions) |
| Mudanças pequenas e reversíveis | blue/green + rollback automático; canary na API | ECS deployment circuit breaker + **blue/green nativo do ECS** ✅, API Gateway canary, CodeDeploy (Lambda) |
| Observabilidade | traces, métricas, logs estruturados c/ `correlation_id`, SLOs | ADOT → X-Ray, CloudWatch Application Signals, EMF metrics, Logs Insights, Synthetics |
| Aprender com falhas | game days, runbooks | Fault Injection Service, Systems Manager Automation (runbooks), Incident Manager |
| CI/CD | build, testes, scan, deploy | CodePipeline/CodeBuild (ou GitHub Actions + OIDC), ECR |
| Lambda | logging/tracing/metrics padronizados | **Powertools for AWS Lambda (Java)** |

### 4.2 Segurança
| Prática WA | Como atendemos | Serviço AWS |
|---|---|---|
| Identidade forte (borda) | OAuth2 client_credentials, scopes por operação; mTLS p/ parceiros | Cognito (resource server/scopes), API Gateway mTLS |
| Identidade forte (interna) | sem credenciais estáticas; least privilege por task/função | IAM task roles, IAM auth no Aurora, Secrets Manager (rotação) |
| Proteção de borda | OWASP, rate-based rules, bots, DDoS | WAF (managed rules), Shield (Advanced opcional) |
| Proteção de rede | subnets privadas, sem internet egress p/ AWS APIs | VPC, Security Groups, **VPC endpoints** |
| Dados em repouso/trânsito | CMK por domínio, TLS 1.2+ | KMS, ACM |
| Rastreabilidade / auditoria (exigência do PDF) | toda chamada de API e toda decisão registrada e imutável | CloudTrail, eventos → S3 **Object Lock (WORM)**, AWS Config |
| Detecção | ameaças, postura, vulnerabilidades | GuardDuty, Security Hub, Inspector (imagens ECR), Macie (PII no S3) |
| LGPD | minimizar PII nos eventos (`id_conta` mascarado/tokenizado p/ consumidores não autorizados) | Lake Formation (column-level) |

### 4.3 Confiabilidade
| Prática WA | Como atendemos | Serviço AWS |
|---|---|---|
| Multi-AZ | tudo em 3 AZs | Fargate, DynamoDB, Aurora Multi-AZ, SQS/EventBridge (regionais) |
| Escalar p/ demanda | target tracking + **scheduled scaling** (início de mês) + pré-aquecimento (Black Friday) | Application Auto Scaling, DynamoDB **warm throughput** ✅ (doc AWS: pré-aquecimento para picos planejados de 10x, 100x ou mais) |
| Proteger contra sobrecarga | throttling na borda, load shedding, fail-fast | API Gateway usage plans/throttling, Resilience4j (circuit breaker → 503, bulkhead) |
| Retries seguros | timeout + retry com jitter **somente** com idempotência | ECS Service Connect (timeout/retry/outlier detection), AWS SDK retry mode, Idempotency-Key |
| Desacoplamento | assíncrono com buffer e DLQ em todo consumidor | EventBridge, SQS (DLQ + redrive), SNS |
| Reprocessamento | replay de eventos após bug/incidente | **EventBridge Archive & Replay**, SQS DLQ redrive |
| Backup / DR | PITR, backups cross-region; DR ativo-passivo | DynamoDB PITR, AWS Backup, Aurora Global Database, Route 53 ARC |
| Consistência no DR | **não** usar Global Tables MREC (eventual, last-writer-wins) ativo-ativo para limites. **MRSC descartado** ✅: exige 3 regiões (ou 2 + witness) e **não está disponível em São Paulo (sa-east-1)**, além de aumentar a latência de escrita. DR **ativo-passivo**: MREC ou AWS Backup cross-region para a região de DR, com failover controlado (cerca de escrita na região primária) | DynamoDB Global Tables (MREC), AWS Backup, Route 53 ARC |
| Validar resiliência | experimentos de falha (AZ, latência DynamoDB, task kill) | Fault Injection Service, Resilience Hub |

### 4.4 Eficiência de Performance
| Prática WA | Como atendemos | Serviço AWS |
|---|---|---|
| Compute certo p/ o perfil | síncrono e sensível a latência → containers sempre quentes; assíncrono → serverless | Fargate (sync), Lambda (async) — EKS descartado (overhead) |
| Banco certo p/ o acesso | key-value write-heavy → DynamoDB; ledger relacional → Aurora | DynamoDB, Aurora PostgreSQL |
| Concorrência sem lock | conditional write + versão | DynamoDB `ConditionExpression` |
| Cold start Java | SnapStart nas Lambdas Java | Lambda SnapStart |
| Menor latência de rede | VPC endpoints, NLB privado, Service Connect | — |
| Medir | teste de carga 5k TPS com p50/p95/p99 | k6 (ou Distributed Load Testing on AWS) |

### 4.5 Otimização de Custos
| Prática WA | Como atendemos | Serviço AWS |
|---|---|---|
| Pagar pelo uso | serverless no assíncrono; Fargate escala por demanda | Lambda, Fargate, DynamoDB on-demand |
| Preço por hardware | ARM64 (~20% mais barato) | Graviton (Fargate, Lambda, Aurora) |
| Compromisso p/ base estável | Savings Plans / capacidade provisionada do DynamoDB quando o perfil estabilizar | Compute Savings Plans, DynamoDB reserved capacity |
| Dados frios | lifecycle e formato colunar | S3 Intelligent-Tiering/Glacier, Parquet via Firehose |
| Sem NAT p/ AWS APIs | VPC endpoints (gateway endpoint DynamoDB é gratuito) | VPC endpoints |
| Governança | tags por domínio/serviço, orçamento e alertas | Cost Explorer, Budgets, Cost Anomaly Detection |

### 4.6 Sustentabilidade
| Prática WA | Como atendemos | Serviço AWS |
|---|---|---|
| Hardware eficiente | Graviton em todo compute | Graviton |
| Sem ociosidade | scale-in agressivo fora de pico; Lambda escala a zero | Auto Scaling, Lambda |
| Dados | retenção/TTL definidos, lifecycle, compressão | DynamoDB TTL, S3 lifecycle |
| Medir | pegada de carbono | Customer Carbon Footprint Tool |

### 4.8 Análise de compute: híbrido ou modelo único? (2026-10-20) ✅
Pergunta do usuário: qual é o melhor para o Itaú (custo, eficiência, manutenção), híbrido ou um modelo só, e se fica tudo em Java.

**Opções avaliadas**
- **A. Híbrido com regra:** Fargate no síncrono e no consumo de alto volume constante; Lambda na "cola" de eventos (baixo volume ou irregular)
- **B. Só Fargate:** inclusive ACL, reconciliador e stubs como containers
- **C. Só Lambda:** um Lambda único (autorização + limites) no síncrono e Lambdas no assíncrono
- **D. Lambda Managed Instances no síncrono:** Lambda sobre EC2 gerenciado, sem cold start, multi-concorrência, preço EC2 + 15%

**Fatos validados (doc AWS + Price List API, sa-east-1, preços de lista on-demand)**
- Lambda ARM: US$ 0,20/milhão de requisições + US$ 0,0000133334/GB-s; provisioned concurrency ARM US$ 0,0000053098/GB-s
- Fargate ARM: US$ 0,0557/vCPU-h + US$ 0,00612/GB-h, ou seja, task 1 vCPU/2 GB ≈ **US$ 49,6/mês**
- API Gateway REST: US$ 4,25/milhão (até 1 bi) e US$ 3,61/milhão (1 a 20 bi); HTTP API: US$ 1,59 e US$ 1,43/milhão
- Lambda Managed Instances: "ideal for steady-state workloads **without unexpected traffic spikes**"; "traffic doubling within **five minutes**". Conflita com os picos imprevisíveis do PDF

**Estimativa mensal** (hipótese: média de 1.500 TPS, pico de 5.000 ⇒ **3,94 bi de requisições/mês**; ordem de grandeza, sem descontos EDP/Savings Plans):
| Item | A. Híbrido | B. Só Fargate | C. Só Lambda | D. LMI |
|---|---|---|---|---|
| Compute síncrono | ~US$ 0,9 mil (≈18 tasks médias) | ~US$ 0,9 mil | ~US$ 3,4 mil (único) a 6,0 mil (encadeado) | EC2 + 15%, próximo do Fargate |
| Contabilização | ~US$ 0,15 mil (3 tasks) | ~US$ 0,15 mil | ~US$ 1,1 mil + RDS Proxy | — |
| Cola (ACL, reconciliador) | ~US$ 30 (Lambda) | ~US$ 37 (3 tasks pequenas) | ~US$ 30 | — |
| **Total compute** | **~US$ 1,1 mil** | **~US$ 1,1 mil** | **~US$ 4,5 a 7,2 mil** (+ US$ 2,8 mil se usar provisioned concurrency para eliminar cold start) | próximo de A |
| API Gateway REST (todas as opções) | **~US$ 14,9 mil** | idem | idem | idem |

> **Achado:** o custo dominante é o **API Gateway**, não o compute. Compute representa menos de 10% na opção A. Alavancas: desconto EDP, e avaliar se canais **internos** do Itaú podem usar integração privada, deixando o API Gateway para parceiros externos (o trade-off é perder os usage plans nesse caminho). O DynamoDB custa o mesmo em todas as opções e será dimensionado na Fase 4.

**Matriz de decisão** (nota de 1 a 5; pesos refletem as prioridades de um banco)
| Critério (peso) | A. Híbrido | B. Só Fargate | C. Só Lambda | D. LMI |
|---|---|---|---|---|
| Latência / p99 (25%) | 5 | 5 | 3 (cold start) | 4 |
| Custo (20%) | 5 | 5 | 2 | 4 |
| Manutenção / operação (20%) | 4 (dois modelos de deploy) | 5 (um só modelo) | 3 | 3 (modelo novo, multi-concorrência exige thread safety) |
| Resiliência a picos (15%) | 5 | 4 (consumidores escalam por métrica de fila) | 4 | 2 (dobra só em 5 min) |
| Aderência ao PDF / defesa (10%) | 5 (mostra os dois modelos com regra) | 4 | 3 | 3 |
| Skills e governança (10%) | 4 | 5 | 3 | 2 |
| **Nota ponderada** | **4,70** | **4,75** | **2,95** | **3,20** |

**Leitura:** A e B empatam tecnicamente. C e D ficam claramente atrás para este caso.

**Recomendação para o Itaú: A, híbrido com regra explícita.** Na prática, é "B + Lambda só onde ele elimina código":
- **Fargate** quando está no caminho síncrono **ou** tem volume alto e constante com banco relacional: `autorizacao-service`, `limites-service` e **`contabilizacao-service`**. A contabilização **mudou de Lambda para Fargate**: ela recebe o mesmo volume da autorização (1 evento por transação), e no Fargate usa pool fixo de conexões no Aurora e insert em lote, sem RDS Proxy. Fica cerca de 7 vezes mais barata
- **Lambda** quando é disparado por evento, com volume baixo ou irregular, e o gatilho nativo elimina código de infraestrutura: `corebanking-acl`, reconciliador (EventBridge Scheduler), stubs. Ganha escala a zero e gatilhos SQS/Scheduler com falha parcial de lote nativa
- Por que não B puro: em ACL e reconciliador, o Lambda elimina loop de polling, agendador e capacidade ociosa, com custo e esforço de manutenção desprezíveis

**Linguagem: só Java 21 (sem poliglotismo)**
- Um toolchain (build, SCA/SAST, imagens), uma skill base, bibliotecas compartilhadas (VO `Dinheiro`, envelope CloudEvents, Problem Details) e o CDK na mesma linguagem
- Poliglotismo (Python/TypeScript nos Lambdas, por cold start menor) não se paga com 2 ou 3 funções pequenas: o SnapStart para Java já resolve e não tem custo extra
- Futuro: GraalVM native image é opção para cold start mínimo, com custo de build maior. Não usar agora

### 4.7 Lentes aplicadas
- **Financial Services Industry Lens:** auditoria imutável, segregação de funções, resiliência regulatória (DR testado), criptografia com CMK, rastreabilidade ponta a ponta
- **Serverless Lens:** Lambdas idempotentes, DLQ, Powertools, SnapStart, concorrência reservada
- **Entregável extra:** executar revisão no **AWS Well-Architected Tool** (com FSI Lens) e anexar o relatório + plano de melhorias ao ZIP

---

## 5. Decisões arquiteturais (ADRs)

| # | Decisão | Pilar(es) | Status | Justificativa / trade-off |
|---|---|---|---|---|
| 000 | Well-Architected (6 pilares + FSI + Serverless Lens) como base; preferir serviço gerenciado | todos | P | Menos operação própria, padrão auditável; trade-off: acoplamento à AWS (mitigado por hexagonal + CloudEvents) |
| 001 | Bounded contexts: Autorização, Limites, Contabilização, ACL Core Banking | Op. Excelência | P | Fronteiras por agregado/invariante |
| 002 | Fargate (Graviton) no síncrono; Lambda no assíncrono; EKS descartado | Performance, Custo | P | Sem cold start, p99 previsível a 5k TPS; Lambda elástica; EKS = overhead operacional |
| 003 | DynamoDB para limites com conditional write + versão | Performance, Confiabilidade | P | Concorrência sem lock distribuído; partição por `id_contrato` |
| 004 | Outbox **por serviço** (`TransactWriteItems`) + Streams + **EventBridge Pipes** | Confiabilidade | **R → 026** | Elimina dual-write sem código de publicação; outbox não atravessa serviços |
| 005 | Idempotência: `IDEMP#key` + hash do payload, TTL | Confiabilidade | **R → 027** | Mesma chave+payload → 200; payload diferente → 422 |
| 006 | EventBridge (roteamento) + SQS por consumidor + SQS FIFO p/ contabilidade + **Archive & Replay** | Confiabilidade | **R → 028** | Ordenação só por contrato; replay gerenciado; Kinesis/MSK só se volume/replay de longo prazo exigirem |
| 007 | API Gateway **REST** (não HTTP API) + Cognito + WAF + usage plans | Segurança, Confiabilidade | P | HTTP API não tem usage plans/quotas/WAF/request validation |
| 008 | Aurora PostgreSQL (Graviton, RDS Proxy, IAM auth) na Contabilização | Performance | P | Ledger relacional, ACID, relatórios regulatórios |
| 009 | Gateway na borda; **sem service mesh** — leste-oeste via **ECS Service Connect**; BFF só com canais distintos | Op. Excelência, Custo | P | **App Mesh descontinuado** ✅ (fim do suporte em 30/09/2026, conforme doc AWS); Service Connect dá discovery/retry/timeout/outlier sem sidecar próprio; alternativa: VPC Lattice |
| 010 | Multi-AZ ativo (3 AZs em sa-east-1); DR ativo-passivo com failover controlado | Confiabilidade | P | MREC = last-writer-wins → risco de limite inconsistente em ativo-ativo; MRSC indisponível em sa-east-1 ✅. Trade-off: RTO de minutos no DR regional |
| 011 | BIAN v12/v13 **validado** como referência: 4 SDs núcleo (Transaction Authorization, Position Keeping, Limit and Exposure Management, Financial Accounting) → 3 BCs + ACL; 4 serviços + 2 stubs | Op. Excelência | P | Vocabulário de mercado validado na fonte oficial; evita nanoserviços (SD ≠ serviço) |
| 012 | Reserva idempotente por `id_autorizacao` + reconciliador (EventBridge Scheduler + Lambda) | Confiabilidade | **R → 027** | Substitui 2PC; consistência eventual só em falha parcial |
| 013 | DynamoDB na Autorização (single-table `AUT#`, `IDEMP#`, `OUT#`; GSI `id_contrato`+`occurred_at`) | Performance, Confiabilidade | **R → 027** | Key-value write-heavy (~15k WCU), TTL, Streams; contras: sem query ad hoc (→ CQRS/Athena), transação ≤100 itens e 2x custo, pré-aquecer p/ picos. Alternativa: Aurora + RDS Proxy |
| 014 | Eventos **CloudEvents 1.0** no `detail` do EventBridge; `type` reverse-DNS c/ major; extensões `traceparent`, `correlationid`, `partitionkey`; dinheiro como string; **AsyncAPI**; **Glue Schema Registry** (FULL; era BACKWARD, corrigido em 2026-10-21) | Op. Excelência | P | Padrão aberto/portável; EventBridge Schemas só p/ descoberta |
| 015 | Observabilidade: **ADOT** (OpenTelemetry) → X-Ray + CloudWatch Application Signals com SLOs; Powertools nas Lambdas | Op. Excelência | P | Padrão aberto (OTel) com backend gerenciado |
| 016 | Trilha de auditoria imutável: eventos → Firehose → S3 Object Lock (compliance mode) + CloudTrail | Segurança | P | Requisito de auditoria/rastreabilidade do PDF e regulatório |
| 019 | Linguagem **Java 21** (Spring Boot 3 nos serviços Fargate; Java puro + Powertools nos Lambdas) | Op. Excelência, Performance | A | Decisão do usuário; ecossistema maduro em bancos, SnapStart sem custo para Java, CDK na mesma linguagem |
| 020 | IaC com **AWS CDK em Java** (sintetiza CloudFormation), stacks por domínio (rede, dados, compute, eventos, API, observabilidade, segurança), **cdk-nag** AwsSolutions no build; local via `cdklocal` | Op. Excelência, Segurança | **R → 029** | Decisão do usuário: IaC nativo AWS. PDF não exige ferramenta. Mesma linguagem dos serviços; rollback/drift do CloudFormation; constructs L2 com defaults seguros e `grant*` (least privilege). Alternativas: CloudFormation puro (verboso), SAM (só serverless), Terraform (multicloud, padrão enterprise; descartado por ADR 000 AWS-first) |
| 022 | **Lambda avaliado para o caminho síncrono e mantido fora dele**: Fargate em autorização e limites; Lambda (Java puro + Powertools + SnapStart, sem Spring) em ACL, reconciliador e stubs (contabilização foi para Fargate no ADR 023) | Performance, Custo, Confiabilidade | A | Validado na doc AWS: concorrência padrão de 1.000 (aumentável); escala de 1.000 ambientes a cada 10 s; SnapStart sem custo para Java mas **incompatível com provisioned concurrency**; API GW 10k RPS em sa-east-1. Motivos: cauda do p99 (cold start), circuit breaker por ambiente de execução (fraco), anti-padrão Lambda→Lambda síncrono, custo maior com volume constante (estimativa grosseira: Lambda ~US$ 3,6 mil/mês vs Fargate ~US$ 0,5–1 mil/mês, a validar na Pricing Calculator). **Alternativa B:** um único Lambda com autorização+limites (monólito modular, uma `TransactWriteItems`, sem saga), viável e documentada. A hexagonal permite trocar só o adapter de entrada |
| 023 | **Compute híbrido com regra explícita**: Fargate = caminho síncrono **ou** volume alto e constante com banco relacional (autorização, limites, **contabilização**); Lambda = gatilho por evento com volume baixo/irregular (ACL, reconciliador, stubs). **Só Java 21** | Custo, Performance, Op. Excelência | A | Matriz ponderada: A 4,70 · B 4,75 · C 2,95 · D 3,20 (§4.8). A e B empatam; A elimina código de polling/agendamento onde o Lambda é nativo. Contabilização em Fargate: ~7x mais barata que Lambda + RDS Proxy no volume de 1 evento por transação. Achado: o custo dominante é o API Gateway (~US$ 14,9 mil/mês), não o compute |
| 024 | **Concorrência sem lock distribuído**: escrita condicional no item (`disponivel >= :v`), `TransactionConflict` → retry com jitter → 409, idempotência por `Idempotency-Key` e por `id_autorizacao`, máquina de estados com guarda, inbox no consumidor, versão monotônica para eventos do legado (catálogo C1–C11, §3.7) | Confiabilidade | P | Evita lost update e reserva dupla sem nova peça de infra; trade-off: contrato muito quente exigiria sub-limites fragmentados |
| 025 | **EDA**: único acoplamento síncrono é autorização → limites; todo o resto por eventos (catálogo §3.8); implementar outbox + Pipes + bus + regras + DLQ + Archive & Replay + Schema Registry + consumidores contabilização e limites; antifraude e notificações como stubs | Confiabilidade, Op. Excelência | P | Regra: precisa da resposta para responder → síncrono; fato consumado → evento |
| 026 | **Outbox embutido no item** (atributos `evento_*`) + **relay Lambda** (DynamoDB Streams → Kinesis/EventBridge), em vez de EventBridge Pipes | Custo, Confiabilidade | A | Pipes US$ 0,69/milhão (~US$ 5,4 mil/mês) × relay ~US$ 0,1 mil; sem dual-write; funciona no LocalStack (detalhe: apresentacao-itau/docs/adr) |
| 027 | **id_autorizacao = UUIDv5(cliente, Idempotency-Key)**; sem PENDENTE/GSI; verificação posterior em SQS (15 min) + reconciliador; Limites aceita repetição só até 10 min (**janelas disjuntas**) | Custo, Confiabilidade | A | −13 WRU/aut. vs v1; elimina corrida retentativa × reversão |
| 028 | **Kinesis** para o fluxo transacional (partition key id_contrato, 8 shards, 7 d); **EventBridge** só para integração entre domínios | Custo, Confiabilidade | A | Quota padrão sa-east-1: PutEvents 600/s, invocações 1.100/s (doc AWS); Kinesis ~US$ 0,7 mil/mês com ordem por chave |
| 029 | **Terraform** (substitui CDK, decisão do usuário); módulos rede/dados/eventos/compute/funcoes/api/observabilidade; ambientes prod e local (LocalStack) | Op. Excelência | A | `terraform validate` ok nos dois ambientes |
| 030 | **Aurora I/O-Optimized** (r7g.2xlarge) e **TTL 35 d** no DynamoDB | Custo | A | I/O avulso ~US$ 8,8 mil/mês; sem TTL o DynamoDB cresce ~6 TB/mês |
| 031 | **API Gateway criado por importação do OpenAPI** (módulo `openapi-api-gateway`, OpenAPI 3.0.3) + **oasdiff** no CI | Op. Excelência, Confiabilidade | A | API Gateway REST importa só OpenAPI 2.0/3.0 e não suporta `exclusiveMinimum` (doc AWS); a AWS não tem registry de OpenAPI; portals = recomendação |
| 017 | Resilience4j mantido no código (circuit breaker → 503, bulkhead) | Confiabilidade | P | Não há equivalente gerenciado in-process; Service Connect complementa com outlier detection |
| 018 | **Position Keeping no BC Limites** (não na Contabilização); `LimiteContrato` = Financial Position Log, `Reserva` = Amount Block, `Movimento` = Financial Transaction Capture | Confiabilidade, Performance | P | BIAN: PK "maintain positions… includes limits and blocks". A reserva e a checagem precisam ser atômicas no mesmo agregado. Substitui a decisão anterior (PK na Contabilização), que estava **errada** |

---

## 6. Stack

| Item | Escolha | Status |
|---|---|---|
| Linguagem / framework | **Java 21** — Spring Boot 3 nos serviços Fargate; Java puro + Powertools + SnapStart (sem Spring) nos Lambdas | **A** (ADR 019, 022) |
| IaC | **Terraform** (módulos por domínio técnico; ambientes prod e local) | **A** (ADR 029, substitui 020) |
| Estrutura | Multi-módulo hexagonal: `domain` (puro), `application` (use cases + ports), `adapters` | P |
| Testes | JUnit 5, ArchUnit, Testcontainers + LocalStack | P |
| Resiliência | Resilience4j + ECS Service Connect + AWS SDK retry | P |
| Observabilidade | ADOT/OpenTelemetry, X-Ray, Application Signals, EMF | P |
| Contratos | OpenAPI 3.1 (REST), AsyncAPI 3 (eventos), JSON Schema no Glue Registry | P |
| Carga | k6 | P |
| Diagramas | C4 (Mermaid / Structurizr) + diagrama AWS (ícones oficiais) | P |

### Ambiente local (demo)
| Serviço AWS | Local | Observação |
|---|---|---|
| DynamoDB + Streams, SQS (std/FIFO), SNS, EventBridge, Lambda, Firehose→S3, API Gateway REST | LocalStack | OK; Pipes com suporte parcial — fallback: Lambda lendo o Stream |
| ECS Fargate | docker-compose | Mesma imagem roda local e na AWS |
| Aurora PostgreSQL | container `postgres` | Compatível |
| Cognito | Keycloak (OAuth2 client_credentials) | Cognito no LocalStack é pago |
| X-Ray / Application Signals | ADOT collector → Jaeger | Mesmo SDK OTel |
| WAF, Shield, GuardDuty, Security Hub, FIS | só na AWS (IaC + slides) | — |
| IaC | `cdklocal` → LocalStack | Mesmas stacks CDK local e AWS |

⚠️ Verificar licenciamento atual do LocalStack (auth token / plano gratuito).

---

## 7. Plano de execução

> Plano detalhado: [PLANO-IMPLANTACAO.md](PLANO-IMPLANTACAO.md). A tabela abaixo é o resumo.

| Dia | Entregas | Status |
|---|---|---|
| 1 | Event storming, OpenAPI, AsyncAPI/CloudEvents, linguagem ubíqua, C4 + diagrama AWS, ADRs, NFRs assumidos | ⏳ não iniciado |
| 2 | Domínio + casos de uso (Limites, Autorização) + testes unitários + ArchUnit | ⏳ |
| 3 | Adapters (DynamoDB, outbox, Pipes/EventBridge), idempotência, Problem Details, Resilience4j | ⏳ |
| 4 | Contabilização (Fargate+Aurora), ACL mock, reconciliador, docker-compose, IaC | ⏳ |
| 5 | Teste de carga, observabilidade, revisão no **WA Tool**, ADRs finais, roteiro da apresentação | ⏳ |

---

## 8. Pendências / perguntas abertas

- [x] ~~Confirmar linguagem~~ → **Java 21** (2026-10-20)
- [x] ~~Confirmar IaC~~ → **Terraform** (ADR 029, substitui o CDK da ADR 020)
- [ ] Confirmar NFRs assumidos (p99, disponibilidade, RPO/RTO)
- [x] ~~Definir semântica de ESTORNO e CREDITO sobre o limite~~ → ADR 021
- [x] ~~Validar nomes BIAN, App Mesh EOL, ECS blue/green, warm throughput, MRSC~~ → validado em 2026-10-20 (seções 3.1 e 4.0)
- [ ] Confirmar com o time a região de DR (residência de dados, Res. BCB/CMN 4.893) — impacta ADR 010
- [x] ~~Decidir se a demo final roda só local ou também em conta AWS~~ → **só local** (usuário não pode subir nada em conta nenhuma, 2026-10-21)
- [ ] **Escolher o runtime de containers da máquina** (Docker Desktop instalado mas parado; WSL sem distribuição; checar licença corporativa) e se o LocalStack exige `LOCALSTACK_AUTH_TOKEN`
- [ ] Decidir a imagem do LocalStack: `4.0` fixada (sem conta) × versão atual com conta **Hobby** (não comercial; checar se cabe no uso)
- [ ] **Executar a demo local** (`local/subir.sh` + `demo.sh`, inclui `terraform apply` no LocalStack) — depende do item acima
- [x] ~~Testes de integração dos adaptadores DynamoDB~~ → `DynamoDbPosicaoRepositoryIT` (Testcontainers, `amazon/dynamodb-local:3.3.2`, perfil `-Pintegracao`); **executar na máquina com Docker**
- [ ] Rodar o teste de carga `tests/k6`
- [ ] Desvios do PDF a defender: `TransacaoAutorizada` no Kinesis (PDF diz EventBridge) e SNS fora do fluxo — ver `apresentacao-itau/docs/visao-da-solucao.md` §6
- [x] ~~Propagar `correlation_id` Autorização → Limites e LimiteConcedido → LimiteAlterado~~ → feito (header + MDC; `EventosParaCloudEventTest`)
- [ ] Rodar o oasdiff (`contracts/verificar-contrato-api.sh`) quando houver binário ou Docker
- [ ] Importação do OpenAPI no API Gateway nunca aplicada de verdade (só `terraform test`); API Gateway/Cognito/WAF não sobem no ambiente local
- [ ] API Gateway portals e documentation versions: recomendação (portal não é criado via Terraform, segundo a doc)
- [x] ~~Gerar o ZIP~~ → `c:/workdir/itau/itau-pod99-solucao.zip` (sem binários; `.js` renomeado para `.js.txt` por causa do Gmail)
- [ ] **Testar na máquina não corporativa** seguindo `apresentacao-itau/COMO-RODAR.md` (subir, demo, `-Pintegracao`)

- [ ] **Executar o plano de correções da auditoria** (`docs/CORRECOES-AUDITORIA.md`, C01–C12; decisões D1–D3 com o usuário)

---

## 9. Perguntas prováveis na defesa

- Por que DynamoDB e não Aurora para limites/autorização? (conditional write, escala de escrita, TTL, Streams)
- Como garantir que o evento sai se a transação comitou? (outbox + Streams + Pipes)
- Por que não `PutEvents` direto no request? (dual-write; acopla latência/disponibilidade ao EventBridge)
- Outbox explícito vs CDC da tabela? (evento é contrato público; tabela é detalhe interno)
- Por que não Global Tables ativo-ativo? (LWW; MRSC como alternativa c/ custo de latência)
- 402 vs 409? (409 quando a condição falha por versão após retries)
- Por que REST e não HTTP API? (usage plans, WAF, request validation)
- Quando migrar de EventBridge para Kinesis/MSK? (replay longo, throughput muito maior, ordenação global por partição)
- CQRS: onde sim, onde não?
- Por que não service mesh? (2–3 serviços; App Mesh descontinuado; Service Connect/VPC Lattice)
- Como o WA guiou cada decisão? (seção 4 + ADR coluna "pilar")
- Lock-in AWS? (hexagonal isola SDK; CloudEvents/OTel/OpenAPI são padrões abertos)
- Por que Position Keeping em Limites? (BIAN: "includes limits and blocks"; Amount Block = Reserva; checagem + reserva atômicas no mesmo agregado)
- Por que não Credit Facility? (Trade Banking, acordo corporativo guarda-chuva; serve no futuro para hierarquia de limites)
- Por que não Card Authorization? (específico de cartão/bandeira; o PDF é multiproduto)
- Onde está o consórcio no BIAN? (não existe SD; lacuna documentada, tratado como produto do legado via ACL)
- O Transaction Authorization do BIAN faz fraude inline: por que vocês não? (orçamento de latência a 5k TPS; Fraud Evaluation assíncrono; score inline como evolução)
- Por que Financial Accounting é "Risk and Compliance" no BIAN? (contabilidade é obrigação regulatória: reforça auditoria e rastreabilidade)
- Por que não Lambda na autorização? (p99/cold start, SnapStart ≠ provisioned concurrency, circuit breaker por ambiente, Lambda→Lambda, custo com volume constante; alternativa B com Lambda único; hexagonal troca só o adapter)
- Por que híbrido e não tudo Fargate ou tudo Lambda? (regra explícita; matriz §4.8; contabilização em Fargate por volume constante + Aurora)
- Qual é o maior custo da solução? (API Gateway REST ~US$ 14,9 mil/mês na hipótese de 1.500 TPS médios; compute < 10%)
- Por que não Lambda Managed Instances? (a AWS indica para carga estável sem picos inesperados; dobra a capacidade em ~5 min)
- Como evitam lost update no limite? (update atômico com condição no valor, não ler-alterar-gravar; C1)
- Quando sai 409 e quando sai 402? (402: condição de saldo falhou; 409: TransactionConflict após retries ou mesma Idempotency-Key em processamento)
- Por que não lock distribuído / versão otimista no débito? (§3.7)
- E se o legado reduzir o limite abaixo do já usado? (disponível negativo bloqueia novas, não desfaz aprovadas)
- Onde tem EDA e onde não tem? (só autorização → limites é síncrono; §3.8)
- Por que não MRSC no DR? (indisponível em sa-east-1, exige 3 regiões e aumenta a latência de escrita)

---

## 10. Histórico de sessões

### 2026-10-20 — Sessão 1
- Lido o PDF e extraídos requisitos; proposta inicial de arquitetura, stack e plano.
- Criados `docs/MEMORIA.md` e `CLAUDE.md`.
- Avaliado LocalStack para demo local.
- Mapeamento BIAN → 4 microsserviços + 2 stubs; context map; mini-saga Autorização↔Limites.
- Justificado DynamoDB na Autorização (ADR 013); explicado papel do EventBridge/outbox; CloudEvents (ADR 014).
- **Documento reestruturado com o AWS Well-Architected como base** (seção 4 por pilar, ADR 000, coluna "pilar" nos ADRs); incorporados serviços gerenciados: Pipes, Archive & Replay, Service Connect, ADOT/Application Signals, Powertools, SnapStart, Graviton, VPC endpoints, Object Lock, FIS, Resilience Hub, WA Tool (ADRs 015–017).
- Criado diagrama pirâmide LEGO BIAN → DDD (`docs/diagramas/`). Edge headless bloqueado na máquina — PNG não gerado; abrir SVG no navegador.
- Incluídos **Position Keeping** (→ Contabilização) e **Current Account** (→ ACL) no mapeamento BIAN e na pirâmide; camada ③ agora mostra o BC de destino de cada SD.
- **Validação do BIAN nas fontes oficiais** (matriz v12 e v13, definições, Control Records, Semantic APIs no GitHub): corrigidos Credit Facility (Trade Banking, corporativo, não usado), Position Keeping (→ Limites, ADR 018), Financial Accounting (Regulations and Compliance), Fraud Detection → Fraud Evaluation. Documentado SD a SD por que foi usado ou descartado (seções 3.1 a 3.6)
- **Validação do Well-Architected na doc pública da AWS** (seção 4.0): confirmados REST vs HTTP API, Pipes, Archive & Replay, warm throughput, ECS blue/green, Service Connect, SnapStart, App Mesh descontinuado; **MRSC descartado** (indisponível em sa-east-1)
- Torre LEGO refeita com a hierarquia validada
- Criado **[PLANO-IMPLANTACAO.md](PLANO-IMPLANTACAO.md)**: 15 fases (0–14) com passos, entregáveis, critério de pronto, cronograma de 5 dias, diagramas Mermaid (roadmap, context map, containers AWS, sequência da autorização), roteiro de demo e matriz de rastreabilidade PDF → fase → artefato. Novos ADRs previstos: 019 (linguagem), 020 (IaC), 021 (semântica de `tipo_operacao`)
- **IaC decidido: AWS CDK em Java** (ADR 020). O PDF não exige ferramenta específica
- **Linguagem decidida: Java 21** (usuário, 2026-10-20)
- Avaliado Lambda no caminho síncrono pela ótica de arquiteto AWS (doc oficial): cabe tecnicamente com aumento de quota, mas Fargate segue no síncrono; Lambda no assíncrono. Alternativa B (Lambda único, monólito modular) documentada (ADR 022)
- **Análise híbrido vs modelo único** com preços de sa-east-1 (Price List API) e doc AWS (incl. Lambda Managed Instances): recomendado **híbrido com regra**, só Java 21; contabilização passou para Fargate (ADR 023, §4.8). Achado: o custo dominante é o API Gateway
- **Concorrência** detalhada (catálogo C1–C11, mecanismos, testes) e **mapa de EDA** (catálogo de eventos, o que implementar e o que é stub): §3.7, §3.8, ADRs 024 e 025
- Aguardando: semântica de `tipo_operacao` (ADR 021)

### 2026-10-20 — Sessão 2: implementação (pasta `apresentacao-itau/`)
- **IaC trocado para Terraform** (pedido do usuário) — ADR 029 substitui a 020.
- Revisão de custo com Price List API sa-east-1 (DynamoDB, EventBridge/Pipes, Kinesis, SQS, SNS, Firehose, WAF, RDS)
  e quotas da doc AWS: **EventBridge sa-east-1 = 600 PutEvents/s e 1.100 invocações/s** ⇒ Kinesis no fluxo
  transacional (ADR 028); Pipes trocado por relay Lambda (ADR 026); id determinístico sem PENDENTE (ADR 027);
  Aurora I/O-Optimized + TTL (ADR 030). Modelo: **~US$ 121 mil → ~US$ 35 mil/mês (−71%)**, US$ 8,90/milhão.
- Encontrada e fechada corrida retentativa × reconciliador com **janelas disjuntas** (10 min × 15 min).
- Implementado: `kernel-compartilhado`, `linguagem-publicada` (com teste de contrato contra JSON Schemas),
  `autorizacao-service`, `limites-service`, `contabilizacao-service` (KCL + Aurora + inbox), Lambdas `outbox-relay`,
  `reconciliador`, `corebanking-acl` (layout posicional do legado), `consumidores-stub`. **77 testes verdes**
  (inclui 50 débitos simultâneos no mesmo contrato, idempotência concorrente, ArchUnit).
- Terraform: módulos `rede`, `dados`, `eventos`, `compute`, `funcoes`, `api`, `observabilidade`; `validate` ok em prod e local.
- Ambiente de build da máquina: Maven/Terraform em `~/tools`; JVM usa truststore próprio com as CAs corporativas
  (Zscaler) — `source ~/tools/ambiente-build.sh`. Docker daemon não estava ativo: demo local não executada aqui.

### 2026-10-21 — Sessão 3: visão da solução e aderência
- Criado `apresentacao-itau/docs/visao-da-solucao.md` (público: banca): resumo executivo, funcionamento em 12 passos,
  componentes com BIAN/DDD, **matriz de aderência requisito a requisito do PDF**, desvios conscientes, pendências.
- Criado diagrama numerado `apresentacao-itau/docs/diagramas/fluxo-passo-a-passo.svg` (gerado por
  `gerar_fluxo_passo_a_passo.py`; prévia renderizada com Chrome headless).
- Desvios identificados na revisão do PDF: (1) `TransacaoAutorizada` publicado no Kinesis, e não no EventBridge
  (quota de 600 PutEvents/s em sa-east-1, ordem por contrato, custo); (2) SNS só nos alarmes, sem uso no fluxo de
  eventos. Ambos estão justificados no doc, com alternativa.
- Lacuna registrada: os adaptadores DynamoDB reais não têm teste automatizado (os testes usam fakes); proposta:
  DynamoDB Local embutido.
- Revisão da governança de eventos (pedido do usuário): nova §5 em `visao-da-solucao.md` (CloudEvents = envelope;
  JSON Schema = conteúdo; AsyncAPI = catálogo; Glue Schema Registry = cartório). Validado na doc AWS: Glue tem 8
  modos de compatibilidade e JSON Schema Draft-04/06/07, com integração a Kinesis/MSK/Lambda; EventBridge Schemas é
  para descoberta e code bindings (OpenAPI 3 / JSONSchema Draft4). **Correção:** compatibilidade BACKWARD → **FULL**
  (BACKWARD permite remover campos e quebra consumidores antigos de outros times).
- **Correção:** `correlationid` passou a ser obrigatório nos 6 schemas (o PDF diz não nulo); `CodecDeEventos` usa o
  `id` do evento quando não há correlação recebida (eventos do Limites saíam com null). Build: **78 testes verdes**.
- §6.1 reescrita: EventBridge no PDF é "exemplo"; argumentos em ordem de peso (custo e fan-out fortes; quota fraca,
  pois é ajustável; ordem é bônus, já que o PDF exige tolerar reordenação); plano B documentado.
- Versionamento dos contratos explicado em `visao-da-solucao.md` §5.6: fonte da verdade = repositório (`contracts/`);
  AWS guarda cópias (API Gateway: rota `/v1` + model; Glue: versões numeradas por schema). Major no path/`type`,
  minor em `info.version`/versão do Glue. **Correção:** o stage do API Gateway se chamava `v1` (a URL ficaria
  `/v1/v1/...`); renomeado para `api`, com a dimensão `Stage` dos alarmes vindo de output. `terraform validate` ok.
  `Deprecation`/`Sunset` = política (sem código até existir `/v2`).
- **ADR 031 — contract-first de verdade na API:** novo módulo `infra/terraform/modulos/openapi-api-gateway` (sem
  recursos) lê o OpenAPI e gera o `body` do API Gateway com as extensões `x-amazon-apigateway-*`; 13 recursos manuais
  removidos do módulo `api`. Contrato 3.1.0 → **3.0.3** e `exclusiveMinimum` → `minimum: 0.01` (limitações do API
  Gateway, validadas na doc). `terraform test`: 4 cenários verdes; `validate` ok em prod e local. Script
  `contracts/verificar-contrato-api.sh` (oasdiff) criado, não executado. `visao-da-solucao.md` §5.6 e §5.7 atualizadas.
- **Diagnóstico da máquina para a demo local (só leitura, 2026-10-21):** Docker Desktop 4.69 instalado pelo admin
  (abr/2026) e **nunca iniciado** pelo usuário; usuário no grupo `docker-users` (não é admin); WSL 2.7.1 instalado,
  padrão v2, **sem distribuição**; hypervisor ativo; 15,7 GB RAM, 8 núcleos, **27 GB livres em C: (89% usado)**;
  Docker Hub acessível; **TLS interceptado pelo Zscaler** (emissor `PruZSCloudProd.prudential.com`) também para
  Docker Hub e GitHub — risco no `ADD` do agente OTel no Dockerfile e em downloads dentro dos containers; as CAs
  corporativas já exportadas estão em `~/tools/truststore/*.cer`. Sem Rancher/Podman/Moto/Postgres nativo.
- **LocalStack validado na doc oficial (2026-10-21):** os planos agora são **Hobby (grátis, uso não comercial,
  exige conta)**, Base e Ultimate, e o **auth token é obrigatório** para iniciar o container nas versões atuais
  (calendar versioning desde mar/2026). Cobertura por plano: EventBridge (com CreateArchive e replay), Kinesis,
  DynamoDB Streams, Firehose, API Gateway REST → Hobby; **Cognito → Base (pago)**; **Glue, incluindo o Schema Registry →
  só Ultimate (pago)**. O compose fixa `localstack/localstack:4.0` (nov/2024, edição Community antiga, sem token;
  tag ainda ativa no Docker Hub). Decisão pendente: usar a 4.0 sem conta ou a atual com conta Hobby.

### 2026-10-21 — Sessão 3 (cont.): revisão geral para rodar em outra máquina
- **Pontos de falha corrigidos no caminho local (nunca executado):** Lambdas arm64 em máquina x86
  (`LAMBDA_IGNORE_ARCHITECTURE=1`); URLs de fila do LocalStack não resolvíveis dentro dos containers
  (`LOCALSTACK_HOST=localstack:4566`); timeout de partida da Lambda Java; variável de CBOR errada
  (`AWS_CBOR_DISABLE` é do SDK v1 → `AWS_CBOR_ENABLED=false`); KCL em enhanced fan-out/HTTP2 contra emulador
  (novo `KCL_MODO_LEITURA=polling`); KCL começando em LATEST perderia fatos (→ **TRIM_HORIZON**, também em
  produção, porque o inbox deduplica); `demo.sh` dependia de `/proc/sys/kernel/random/uuid` (não existe no Mac
  nem no Git Bash) e esperava só 5 s pela contabilização; `subir.sh` sem pré-checagem nem espera de health;
  estado do Terraform órfão quando o LocalStack é recriado (novo `derrubar.sh` e detecção no `subir.sh`).
- **Implementado:** propagação de `correlation_id` entre contextos; teste de integração do DynamoDB real
  (Testcontainers, perfil `-Pintegracao`; o DynamoDB Local embutido foi descartado por trazer SDK 2.55/Jackson 2.22
  e libs nativas sem Linux ARM).
- **Line endings:** 54 arquivos tinham virado CRLF por edições via Python no Windows; normalizados para LF e
  `.gitattributes` criado.
- **Novos documentos:** `apresentacao-itau/COMO-RODAR.md` (guia para máquina nova), `docs/roteiro-apresentacao.md`
  (20 min + perguntas), pôster `docs/diagramas/poster-solucao.{svg,png}` (gerado por `gerar_poster.py`), PNG do
  fluxo passo a passo. `local/LEIAME.md` reescrito com as diferenças local × AWS.
- Build: **80 testes verdes**; 4 ITs ignorados sem Docker; `terraform validate` ok (prod e local); `terraform test`
  4/4; `fmt` ok.
- ZIP regenerado sem `.sh` (pedido do usuário): `.sh` e `.js` vão como `.sh.txt`/`.js.txt`; `LEIA-ME.txt` e `COMO-RODAR.md` §3.1 trazem o comando que restaura os nomes. Testado: extrair → restaurar → scripts válidos. Gerador: `%TEMP%/gerar_zip.py` (fora do projeto).

### 2026-10-21 — Auditoria técnica externa
- Auditoria do ZIP: **aprovar continuidade com ressalvas**. Verificação contra o código e a doc AWS: os dois P0
  procedem (compensação de órfã depende de SQS best-effort; a "reconciliação diária" citada não existe;
  `PutRecords` + `parallelization_factor` não garantem ordem). Quarentena da contabilização pode perder fato se o
  SQS falhar (KCL pula o lote). Falta `aws_api_gateway_account` (bug de apply). Ajustes à auditoria: a separação
  Autorização × Limites é pedida pelo PDF §3.2; ordem não é requisito (PDF §2.4 exige tolerância a reordenação).
- Plano completo em **`docs/CORRECOES-AUDITORIA.md`** (C01–C12, ordem de execução, custos, testes, aceite).

