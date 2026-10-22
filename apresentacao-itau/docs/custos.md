> **PLANILHA HISTÓRICA — NÃO USAR COMO TCO ATUAL:** os US$ 35 mil/mês abaixo foram estimados para **ECS Fargate e Aurora**. A plataforma-alvo agora usa **EKS Auto Mode e RDS PostgreSQL Multi-AZ**, além de consulta de entitlement e transações de reserva individuais. Consulte [custos EKS/RDS](custos/custos-eks-rds.md). O orçamento dessa nova arquitetura ainda não está fechado.

# Custos

Modelo reproduzível: [`custos/calcular_custos.py`](custos/calcular_custos.py). Preços de lista **sa-east-1**
obtidos na AWS Price List API em 2026-10-20, sem descontos (EDP, Savings Plans, reserved capacity).
Premissa de volume: **1.500 TPS médios** (pico 5.000) ⇒ **3,94 bilhões de autorizações/mês**.

## Resultado

| | Desenho inicial (v1) | Implementado (v2) |
|---|---|---|
| **Total/mês** | **~US$ 121 mil** | **~US$ 35 mil** |
| Por milhão de autorizações | ~US$ 30,70 | **~US$ 8,90** |

```
Desenho implementado (v2)
  API Gateway REST                                       US$ 14.871
  DynamoDB (5 WRU + 3 RRU, provisionado 70%)             US$  8.541
  Aurora I/O-Optimized (2× r7g.2xlarge + 90 d de razão)  US$  5.360
  WAF                                                    US$  2.373
  DynamoDB armazenamento (TTL 35 d, estável)             US$  1.811
  Fargate (18 síncronas + 3 KCL, 2/3 Spot)               US$    972
  Kinesis (8 shards, retenção 7 d, PUT, EFO p/ KCL)      US$    694
  Firehose Kinesis → S3 (trilha)                         US$    229
  Lambda relay do outbox                                 US$    113
  SQS · stubs · EventBridge                              US$    113
```

## Decisões que mais pesaram

| Decisão | Antes | Depois | Efeito/mês |
|---|---|---|---|
| id da autorização derivado da Idempotency-Key (UUID v5) | item de idempotência + PENDENTE + GSI de pendentes + GSI por contrato | **1 item, 1 escrita** | −13 WRU/aut. |
| Outbox embutido no item (atributos `evento_*`) | item `OUT#` em transação (2× WRU) | escrita simples | junto acima |
| Reserva sem evento próprio | `LimiteReservado` no barramento | saldo vai em `TransacaoAutorizada` | −3,9 bi eventos |
| Relay Lambda em vez de EventBridge Pipes | US$ 0,69/milhão | lote de ~100 registros | ~−US$ 5,3 mil |
| Kinesis em vez de EventBridge no fluxo transacional | US$ 1/milhão + fan-out SQS/SNS | 8 shards provisionados | ~−US$ 11 mil |
| DynamoDB PROVISIONED + auto scaling (70%) | on-demand | capacidade seguindo a curva | ~3× mais barato por WRU |
| TTL 35 d no DynamoDB, histórico no S3 | armazenamento crescendo ~US$ 2,2 mil/mês todo mês | estável | custo não cresce |
| Aurora I/O-Optimized | I/O avulso (~4 inserts/fato) | sem cobrança por I/O | ~−US$ 6 mil |
| Fargate Spot na contabilização; Graviton em tudo | x86 on-demand | ARM64, Spot | −20% a −70% no compute |
| Filtros nos event source mappings | toda alteração invoca a Lambda | só o que interessa | registros filtrados não são cobrados |

## O que não muda e como atacar

- **API Gateway REST (~42% do total)**: necessário para usage plans, API keys, validação e WAF (requisitos do PDF).
  Alavancas: desconto negociado (EDP); canais internos do banco por integração privada (ALB/VPC Lattice),
  mantendo o API Gateway para parceiros externos — trade-off: perder o usage plan nesse caminho.
- **WAF**: US$ 0,60/milhão de requisições; avaliar escopo (só tráfego externo).

## Riscos do modelo

- 1.500 TPS médios é premissa; o custo de DynamoDB, Kinesis e API Gateway escala linearmente com ela.
- Número de tasks e classe do Aurora são estimativas até o teste de carga (`tests/k6`).
- Preços de lista; um banco desse porte normalmente tem desconto negociado.

## Sensibilidade e feature flag — auditoria C09 (22/10/2026)

```bash
python3 docs/custos/sensibilidade.py
```

O script varia o **TPS médio hipotético**, não o pico (5.000 TPS pedido pelo PDF), e apresenta
uma **estimativa incremental ilustrativa** caso a ponte opcional Kinesis → EventBridge Pipes →
EventBridge → SNS → SQS esteja ligada. A flag real `habilitar_eventbridge_transacoes` no Terraform
continua desligada por padrão. A coluna de delta considera, para cada fato, uma chamada Pipes,
um evento EventBridge, uma publicação SNS e três requests SQS — **não cobre** DLQs, retries,
retenção, quotas, novos consumidores, fan-out adicional ou custos de novos recursos.

As linhas do script original não consideram integralmente as mudanças C01/C03/C05 (agendamento de
expiração, conciliação diária, exportação S3, Athena, Step Functions, DLQs e rateio por produto),
portanto **os US$ 35 mil são um cenário antigo, não TCO completo nem economia validada**. Antes de
qualquer deploy, obter nova precificação, medição de payloads, tráfego médio por horário e `terraform plan`.
