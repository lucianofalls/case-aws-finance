# Matriz técnica de aceite — priorização para a defesa POD99

| ID | Teste | Pré-condições | Resultado esperado | Situação |
|---|---|---|---|---|
| T01 | `mvn verify` | Java 21 + Maven | Todos os testes unitários/ArchUnit passam | Pendente de execução |
| T02 | `mvn verify -Pintegracao` | Docker + Testcontainers | DynamoDB condicional e concorrência | Pendente |
| T03 | `python3 local/verificar-estatico.py` | Python 3 | Parse e estrutura sem falhas | **Executado e aprovado** |
| T04 | `terraform init -backend=false && terraform validate` em local/prod | Terraform | HCL e referências válidas | Pendente |
| T05 | `bash local/subir.sh` | LocalStack/Postgres/Jaeger | Todos os serviços healthy | Pendente |
| T06 | `bash local/demo.sh` | T05 | 201/200/402/422/503 e autorização concorrente correta | Pendente |
| T07 | Contabilização | T05 | CloudEvent→Kinesis→Inbox→partidas dobradas | Pendente |
| T08 | Falha entre commits | T05 + instrumentação | Nenhuma reserva órfã irreconciliável | Pendente |
| T09 | Falha de SQS/Kinesis | T05 + simulação | Retry / DLQ / replay sem perda do fato | Pendente |
| T10 | Reconciliação diária | Stubs AWS/conta sandbox | Órfãs e eventos faltantes detectados, corrigidos, auditados | Pendente |
| T11 | Aderência EventBridge item 2.4 | ADR-037 | Evento `TransacaoAutorizada` disponibilizado no EventBridge ou desvio aceito formalmente | **Gap aberto** |
| T12 | Fan-out SNS/SQS de negócio | Decisão arquitetura | Consumo desacoplado demonstrado | Parcial |
| T13 | Carga até 5.000 TPS | Ambiente isolado dimensionado | Sem overspend, latência definida e custo medido | Pendente |
| T14 | Regras de TTL e compliance | Aprovação de negócio | Retenção e acesso auditáveis | Pendente |

Os testes não devem disparar GitHub Actions e nenhum `terraform apply` AWS será executado sem autorização.
