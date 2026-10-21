# Fase 2 — Correções verificadas de consistência e compensação

**Escopo:** entrega `itau-pod99-solucao(2).zip` importada integralmente no commit `ad35be77`.

## Verificações importantes da versão recebida
- **Existe** máquina de estados diária de conciliação no Terraform: `modulos/conciliacao/main.tf` cria `aws_scheduler_schedule.diaria`, Step Functions e alarmes.
- **Existe** agendador de verificação por DynamoDB Streams com `ReportBatchItemFailures`, SQS com retentativas e verificação posterior. Não atribuir o problema antigo de ausência de agendamento à nova versão.
- **Existe** documentação de Kinesis `PutRecords` sem garantia de ordem; consumidores devem tolerar reordenação.
- Compensação e autorização seguem **transações independentes por bounded context**, com consistência eventual. Isso exige recuperação testada antes de produção.

## Alterações nesta fase
| Severidade | Arquivo | Alteração |
|---|---|---|
| Alto | `VerificarAutorizacaoIncerta.java` | Resultados desconhecidos do Limites agora falham (não são reconhecidos como sucesso) |
| Médio | `JanelaDeConciliacao.java` | `NOT EXISTS` em lugar de `NOT IN` no anti-join Athena para operações órfãs |
| Médio | `SqsFilaDeVerificacao.java` | Serialização JSON com Jackson em vez de concatenação manual |
| Testes | `VerificarAutorizacaoIncertaTest.java`, `ConciliacaoTest.java`, `SqsFilaDeVerificacaoTest.java` | Testes de regressão para os três ajustes |

## Evidência
- `javac 21` compilou `VerificarAutorizacaoIncerta.java` e `JanelaDeConciliacao.java` isoladamente.
- Validação estática do ZIP: JSON, YAML, XML, SVG, Python e sintaxe Bash sem falhas.
- Testes JUnit adicionados **mas não executados** neste ambiente (Maven não disponível).
- GitHub Actions não será utilizado.

## Limites e continuação
- Testar em Docker/LocalStack o fluxo de órfãs após queda entre commits.
- Testar que falha na SQS, retry de Stream e DLQ realmente acionam recuperação.
- Validar o Terraform com provider real usando `terraform validate` e um `plan` **sem apply**.
- Validar a máquina de estados de reconciliação com dados reais sintéticos S3/Athena/Aurora.
