> **ROTEIRO ORIGINAL DESATUALIZADO:** as menções a ECS Fargate e Aurora são históricas. Para defender a arquitetura escolhida use a [visão EKS/RDS](visao-eks-rds.md), a [ADR-042](adr/042-eks-automode-rds-postgres.md) e o [checklist de validação](STATUS-EKS-RDS.md). Este roteiro não deve ser apresentado sem revisão.

# Roteiro da apresentação (20 minutos)

Apoio visual: [diagramas/poster-solucao.png](diagramas/poster-solucao.png) (a solução em uma página) e
[diagramas/fluxo-passo-a-passo.png](diagramas/fluxo-passo-a-passo.png) (os 12 passos). Os números entre
colchetes são os blocos do pôster.

| Tempo | Bloco | O que dizer (em uma frase) | Mostrar |
|---|---|---|---|
| 0–2 min | [1] Problema | "Autorizar 5.000 transações por segundo sem nunca passar do limite, e avisar a retaguarda sem acoplá-la." | pôster, bloco 1 |
| 2–5 min | [2] Domínio | "Três bounded contexts, um por Service Domain do BIAN. A Autorização decide; o Limites é dono do saldo; a Contabilização lança no razão. O legado entra por uma ACL." | bloco 2 + `docs/dominio.md` |
| 5–10 min | [4] Como funciona | "O único passo síncrono é Autorização → Limites. Todo o resto é evento: o outbox grava a decisão e o evento na mesma escrita, e o relay publica no Kinesis." | fluxo passo a passo (1 a 12) |
| 10–13 min | [5] Decisões | "Cada decisão tem um número por trás: Fargate pela latência, DynamoDB pela escrita condicional, Kinesis pelo custo e pelo fan-out a 5k/s." | bloco 5 + `docs/custos.md` |
| 13–15 min | [6] Garantias | "Idempotência, concorrência sem lock, sem dual-write, at-least-once com deduplicação: cada uma tem teste." | bloco 6 + teste dos 50 débitos |
| 15–17 min | [7] Well-Architected | "Um exemplo implementado por pilar." | bloco 7 + `docs/arquitetura.md` |
| 17–20 min | Demo | "Roda tudo localmente com o mesmo Terraform da produção." | `bash local/demo.sh` |

## Frases para os trade-offs (rodapé do pôster)

- **Kinesis × EventBridge:** "O PDF traz o EventBridge como exemplo e, no §3.3, pede para avaliar Kinesis em alta
  volumetria. Avaliei: a 5.000 eventos/s com quatro consumidores, o EventBridge custaria cerca de 15 vezes mais.
  Ele continua no desenho onde brilha, na integração entre domínios. Plano B: EventBridge com aumento de quota; o
  contrato CloudEvent é o mesmo."
- **SNS:** "O fan-out de alto volume é do Kinesis e o de baixo volume é das regras do EventBridge. O SNS entra
  quando houver notificação real ao cliente (e-mail, SMS)."
- **Schema registry:** "O nome Glue engana: é o schema registry da AWS para streams. Ele recusa versão
  incompatível (modo FULL). O EventBridge Schema Registry serve para descoberta, não governa compatibilidade."
- **Contract-first:** "O API Gateway é criado importando o próprio OpenAPI. Breaking change é barrada no CI com o
  oasdiff."
- **DynamoDB × Postgres:** "DynamoDB para decidir rápido por chave, Postgres para contabilizar e consultar, S3 para
  guardar."

## Perguntas prováveis e onde está a resposta

| Pergunta | Resposta curta | Detalhe |
|---|---|---|
| E se duas transações debitarem o mesmo contrato ao mesmo tempo? | escrita condicional pela versão; a perdedora relê e tenta de novo | `docs/concorrencia.md` (C1–C11) |
| E se o Limites aplicar o débito e a resposta se perder? | id determinístico + Streams de Limites→SQS atrasada→reconciliador; reconciliação diária para falhas longas, sem SLA garantido de 15 min | ADR 027 |
| E se o relay ficar parado? | o Streams guarda por 24 h; há alarme de `IteratorAge` e reconciliação diária pela trilha S3 | `visao-da-solucao.md` §6.3 |
| Por que não Lambda no síncrono? | cold start na cauda, circuit breaker por ambiente, custo 4–6× | ADR 022 |
| Por que não EKS? | operação de cluster sem benefício para 3 serviços | ADR 002 |
| Como evolui um contrato? | campo opcional = mesma versão; breaking = `/v2` ou `.v2`, em paralelo | `visao-da-solucao.md` §5.4 e §5.6 |
| Quanto custa? | ~US$ 35 mil/mês, US$ 8,90 por milhão | `docs/custos.md` |
| Usou IA? | sim, como o PDF permite; cada decisão está registrada com o porquê e as fontes oficiais | ADRs + `MEMORIA.md` |


### Nota do apresentador — mudanças C01–C12

A demonstração deve incluir RESERVA, CAPTURA, LIBERACAO e expiração simulada via SQS. Os
mecanismos de Scheduler/DLQ e conciliação S3/Athena em AWS **não são simulados integralmente**.
A ponte Kinesis→EventBridge para `TransacaoAutorizada` é configurável e **OFF por padrão**, pois a
quota e o custo precisam ser validados antes. A defesa deve separar código, validação estática e
prova operacional. Não citar custo de cenário como economia realizada pelo banco.
