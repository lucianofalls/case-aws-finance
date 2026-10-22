# Estado das correções C01–C12 — revisão de 22/10/2026

**Fonte de verdade:** estado do repositório atual após importação do ZIP Claude Code e commits separados de auditoria.
**Regras:** sem GitHub Actions, sem `terraform apply` em AWS; os testes de Maven, LocalStack e Terraform
ficam para execução manual no Mac. Não confundir implementação com teste aprovado.

| Achado | Código / documentação | Status e limite |
|---|---|---|
| C01 compensação e operação órfã | Agendador por Streams do Limites + SQS 15 min + reconciliador, ADRs 027/036 | **Implementado, não validado E2E**; não é prazo garantido |
| C02 ordem Kinesis | Remoção de garantias por `PutRecords`, testes consumidor independente de ordem | **Implementado, testes não executados aqui** |
| C03 >24h Streams | Exportação/conciliação diária já existia; republicador falha em item expirado, runbook da trilha, ADR-041 | **Parcial:** replay S3 é manual, fonte histórica nem sempre contém evento perdido antes do Kinesis |
| C04 EventBridge | Pipe Kinesis→Bus filtrado + SNS→SQS, ADR-040 | **Implementado como opcional OFF**; quotas, custo e E2E pendentes |
| C05 reserva | AmountBlock, CAPTURA, LIBERACAO, expiração; contrato atualizado | **Implementado, testes e ciclo local pendentes** |
| C06 quarentena | KCL/Inbox/quarentena existentes; tipos desconhecidos agora falham fechado | **Implementado, testes de DLQ pendentes** |
| C07 API Gateway logs | API Gateway CloudWatch role | **Implementado no ZIP original, apply não feito** |
| C08 LocalStack | Docker Compose com imagem configurável e portas só localhost | **Preparado, não executado** |
| C09 FinOps | `docs/custos/sensibilidade.py` e alerta de custos omitidos | **Modelo de cenários, não TCO completo** |
| C10 diagramas | `docs/diagramas/fluxos-atuais.md`, diagramas antigos preservados | **Complemento atualizado; PNGs antigos não refletem mudanças** |
| C11 demo | `local/demo.sh` e `local/demo-ciclo-reserva.sh` | **Preparado, não executado**. Expiração enviada diretamente à SQS simula Scheduler |
| C12 docs | OpenAPI externo/interno, domínio, roteiro, visão, ADR 027 e status | **Atualizado; revisão completa ainda necessária após teste** |

## Sequência para teste no Mac, somente após concluir as correções

```bash
git clone https://github.com/lucianofalls/case-aws-finance.git
cd case-aws-finance/apresentacao-itau
python3 local/verificar-estatico.py
mvn clean verify
mvn verify -Pintegracao
terraform -chdir=infra/terraform/ambientes/prod init -backend=false
terraform -chdir=infra/terraform/ambientes/prod validate
bash local/subir.sh           # terraform apply SOMENTE no LocalStack, após revisar endereços
bash local/demo.sh
bash local/demo-ciclo-reserva.sh
bash local/derrubar.sh
```

**Fronteira local:** AWS gerenciada (Scheduler, EventBridge Pipes se plano não suportar,
Cognito/WAF, Aurora, Firehose Object Lock) não é reproduzida integralmente por LocalStack 4.0.
Nunca executar `terraform apply` em `infra/terraform/ambientes/prod`.

## Bloqueadores técnicos remanescentes

1. Executar JUnit, ArchUnit, Testcontainers e testes reais de autorização concorrente.
2. Confirmar entrega de mensagens de verificação, expiração e DLQ; simular perda de resposta depois de commit.
3. Executar prova contábil com lançamentos e reentrega de eventos; validar replay da quarentena.
4. Validar `terraform validate` e `plan` em ambiente de produção sem aplicar recursos.
5. Concluir recuperação automática fora do TTL com base independente de eventos e garantir trilha WORM.
6. Definir SLO, RTO/RPO, quotas Kinesis/EventBridge e revisão IAM/KMS por profissional responsável.
7. Executar carga 5.000 TPS somente em infraestrutura apropriada, jamais no Mac como prova de AWS.
