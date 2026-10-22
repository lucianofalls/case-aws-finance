> **ARQUITETURA ATUAL (22/10/2026): EKS Auto Mode + RDS PostgreSQL Multi-AZ.**
> A descrição anterior de **ECS Fargate e Aurora** neste documento e nos PNGs originais é **histórica**, não é a topologia de produção vigente.
> Para a decisão atual use [ADR-042](docs/adr/042-eks-automode-rds-postgres.md), [visão EKS/RDS](docs/visao-eks-rds.md) e [status de implementação](docs/STATUS-EKS-RDS.md).
> O ambiente local permanece Docker Compose + PostgreSQL em container + LocalStack. Terraform de produção não foi aplicado nem validado.

# Plataforma de Limites e Autorização de Transações — Itaú POD99

Solução do desafio técnico de **Arquiteto de Aplicação** (Nova Plataforma de Finanças, AWS).
Autorização síncrona de transações com reserva de limite por contrato, consistente sob concorrência,
publicando eventos de domínio para contabilidade, antifraude, notificações e data platform.

> **Base:** AWS Well-Architected (6 pilares + Financial Services Industry Lens) · BIAN v12/v13 validado ·
> DDD + Clean/Hexagonal · Java 21 · Terraform · região sa-east-1.

## Visão em 30 segundos

> **Pôster da solução:** [docs/diagramas/poster-solucao.png](docs/diagramas/poster-solucao.png) · **roteiro da defesa:** [docs/roteiro-apresentacao.md](docs/roteiro-apresentacao.md)
>
> **Comece por aqui:** [docs/visao-da-solucao.md](docs/visao-da-solucao.md) — funcionamento passo a passo
> (diagrama numerado) e matriz de aderência a cada requisito do desafio.

```
Canal ─OAuth2+API key─▶ WAF ─▶ API Gateway REST ─VPC Link─▶ NLB ─▶ autorizacao-service (Fargate)
                                                                     │  Service Connect (síncrono, idempotente)
                                                                     ▼
                                                               limites-service (Fargate)
 DynamoDB autorizacoes ──Streams──▶ relay (Lambda) ──▶ Kinesis "transacoes" (chave id_contrato)
                                                         ├─▶ contabilizacao-service (Fargate, KCL) ─▶ Aurora PostgreSQL
                                                         ├─▶ antifraude (stub) · notificações (stub)
                                                         └─▶ Firehose ─▶ S3 Object Lock (auditoria + data platform)
 DynamoDB limites (OUT#) ──Streams──▶ relay (Lambda) ──▶ EventBridge "financas" ─▶ regras ─▶ SQS / Firehose
 Core banking (MQ→SQS) ─▶ corebanking-acl (Lambda) ─▶ EventBridge (LimiteConcedido) ─▶ SQS ─▶ limites-service
```

| Decisão | Por quê (detalhe em [docs/adr](docs/adr/README.md)) |
|---|---|
| **Fargate** no síncrono e na contabilização; **Lambda** na cola por evento | p99 previsível a 5k TPS, circuit breaker com estado, pool fixo no Aurora; Lambda onde o gatilho nativo elimina código (ADR 023) |
| **DynamoDB** por contexto, escrita condicional | concorrência sem lock distribuído; ~5 WRU por autorização (ADR 027) |
| **Kinesis** para o fluxo transacional, **EventBridge** para integração | quota padrão do EventBridge em sa-east-1 (600 PutEvents/s) e custo; Kinesis dá replay por consumidor; ordem não é garantida nem necessária (ADR 028) |
| **Outbox** no próprio item + **relay Lambda** | sem dual-write; ~US$ 100/mês contra ~US$ 2,7 mil do Pipes (ADR 026) |
| **API Gateway REST** + Cognito + usage plans + WAF | REST tem API keys, rate limit por cliente, request validation e WAF (HTTP API não) |
| **Terraform** com módulos por domínio técnico | mesmo código no LocalStack e na AWS (ADR 029) |

## Bounded contexts (BIAN → DDD → código)

| Contexto | BIAN (validado) | Agregado / entidades | Código |
|---|---|---|---|
| Autorização | Transaction Authorization | `Autorizacao` (CR *Interactive Transaction Assessment*) | [servicos/autorizacao-service](servicos/autorizacao-service) · [funcoes/reconciliador](funcoes/reconciliador) |
| Limites | Position Keeping + Limit & Exposure Mgmt | `Posicao` (CR *Financial Position Log*), `Operacao` (BQ *Financial Transaction Capture*) | [servicos/limites-service](servicos/limites-service) |
| Contabilização | Financial Accounting | `RegistroContabil` (CR *Financial Booking Log*), `Lancamento` (BQ *Ledger Posting*) | [servicos/contabilizacao-service](servicos/contabilizacao-service) |
| Core Banking ACL | Sales Product Agreement + produtos do legado | `RegistroDeContratoLegado` → `LimiteConcedido` | [funcoes/corebanking-acl](funcoes/corebanking-acl) |
| Shared kernel / Published language | — | `Dinheiro` · CloudEvents v1 | [bibliotecas](bibliotecas) |

Cada serviço segue **ports & adapters**: `domain` (Java puro) → `application` (casos de uso + ports) →
`adapters` (HTTP, DynamoDB, SQS, Kinesis, JDBC) → `config` (único lugar que conhece Spring/AWS SDK).
**ArchUnit** quebra o build se o núcleo depender de framework. Detalhes em [docs/dominio.md](docs/dominio.md).

## Como rodar

> **Passo a passo completo para uma máquina nova:** [COMO-RODAR.md](COMO-RODAR.md) (sem conta AWS, sem custo).

```bash
bash local/subir.sh         # pré-requisitos → build + 80 testes → LocalStack/Postgres/Jaeger → terraform apply (local) → serviços
bash local/demo.sh          # roteiro: 201 · 200 · 422 · 402 · 50 em paralelo · 503 · eventos → razão · traces
bash local/derrubar.sh      # desliga e apaga o ambiente local
mvn verify -Pintegracao     # opcional: adaptador DynamoDB real via Testcontainers (exige Docker)
```

Produção: `infra/terraform/ambientes/prod` (ver `terraform.tfvars.exemplo`). Diferenças do ambiente local em
[local/LEIAME.md](local/LEIAME.md).

## Mapa do repositório

| Pasta | Conteúdo |
|---|---|
| [contracts/](contracts) | OpenAPI 3.1 (externo e interno), AsyncAPI 3, JSON Schemas dos eventos (Glue Schema Registry) |
| [bibliotecas/](bibliotecas) | shared kernel (`Dinheiro`) e linguagem publicada (CloudEvents + payloads v1 + teste de contrato) |
| [servicos/](servicos) | 3 serviços Spring Boot (Fargate) + Dockerfile único |
| [funcoes/](funcoes) | 4 artefatos Lambda (relay do outbox, reconciliador, ACL, stubs) |
| [infra/terraform/](infra/terraform) | módulos `rede`, `dados`, `eventos`, `compute`, `funcoes`, `api`, `observabilidade`; ambientes `prod` e `local` |
| [local/](local) | docker-compose, subida e roteiro de demo |
| [tests/k6/](tests/k6) | carga até 5.000 TPS e cenário de contenção |
| [docs/](docs) | arquitetura, custos, concorrência, eventos, domínio, ADRs |

## Requisitos do PDF → onde estão

| Requisito | Onde |
|---|---|
| SOLID explícito | [docs/dominio.md#solid](docs/dominio.md#solid-onde-cada-princípio-aparece) |
| Clean/Hexagonal + DIP | pacotes `domain/application/adapters/config` + `ArquiteturaTest` em cada serviço |
| DDD: BCs, agregados, invariantes, linguagem ubíqua | [docs/dominio.md](docs/dominio.md) |
| CQRS / Repository / Domain Events | [docs/dominio.md#padrões-táticos](docs/dominio.md#padrões-táticos) |
| Compute justificado (latência/throughput) | [docs/arquitetura.md#compute](docs/arquitetura.md#compute) |
| Database-per-service | `infra/terraform/modulos/dados` |
| EventBridge, SNS/SQS, Kinesis vs MSK | [docs/eventos.md](docs/eventos.md) |
| API Gateway, OAuth2, usage plans, quotas, 429 | `infra/terraform/modulos/api` |
| Contract-first, versionamento, depreciação | [contracts/openapi/autorizacoes-v1.yaml](contracts/openapi/autorizacoes-v1.yaml) → importado no API Gateway por `infra/terraform/modulos/openapi-api-gateway`; breaking change barrada por `contracts/verificar-contrato-api.sh` |
| Idempotency-Key, RFC 7807, 201/200/402/409/422/429/503 | `AutorizacaoController`, `ProblemDetailsHandler`, gateway responses no Terraform |
| CloudEvents, at-least-once, schema registry, consumidores idempotentes | `linguagem-publicada`, `ContratoDeEventosTest`, inbox da contabilização |
| Consistência sob concorrência | [docs/concorrencia.md](docs/concorrencia.md) |
| ACL com o legado | `funcoes/corebanking-acl` |
| Auditoria e rastreabilidade | S3 Object Lock (COMPLIANCE), `correlation_id`, ADOT/X-Ray |
| Well-Architected | [docs/arquitetura.md#well-architected](docs/arquitetura.md#well-architected-onde-cada-pilar-aparece) |
| Custo | [docs/custos.md](docs/custos.md) |
