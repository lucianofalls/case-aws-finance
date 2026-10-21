# Fase 3 — Infraestrutura, mensageria e prontidão local

**Versão-base:** `itau-pod99-solucao(2).zip` — 244/244 blobs conferidos por SHA Git após importação.

## Preservado (já existente no ZIP)
- Três serviços Java 21 Spring Boot, bounded contexts Autorização, Limites e Contabilização.
- Persistência por contexto: DynamoDB em Autorizações/Limites e Aurora PostgreSQL na Contabilização.
- Lambda relay DynamoDB Streams para Kinesis (transações) e EventBridge (limites).
- `PutRecords` documentado como sem garantia de ordenação; consumidores não podem depender dela.
- Reconciliador por Streams/SQS atrasada e **scheduler diário de conciliação** em `infra/terraform/modulos/conciliacao/main.tf`.
- Terraform de ambientes prod/local, Postgres + LocalStack + Jaeger em Docker Compose.
- Testes Java e scripts do ZIP; não houve execução Maven ou Docker nesta auditoria.

## Correções realizadas
1. Scripts que vinham em `.sh.txt`/`.js.txt` foram renomeados para extensões executáveis/nativas no GitHub. `COMO-RODAR.md` agora instrui clone correto.
2. Todas as portas públicas do Compose foram vinculadas a `127.0.0.1`: 4566, 5432, 8080, 8082, 8083, 16686, 4317.
3. Foi incluído `local/verificar-estatico.py`: execução local verificada sem falhas em JSON, XML, SVG, Python, Bash e PNG; PyYAML, quando instalado, também em YAML.
4. ADR-037 registra que o PDF cita `TransacaoAutorizada` no EventBridge, ao passo que a implementação envia a Kinesis. A ponte para EventBridge está **pendente**, não foi inventada.

## Riscos ainda abertos
| Prioridade | Risco | Próxima ação / evidência |
|---|---|---|
| Alto | Mini-saga Autorização→Limites com 2 commits independentes | E2E de timeout pós-commit, agendamento, reversão e conciliação |
| Alto | EventBridge do exemplo de evento obrigatório versus implementação Kinesis | Fechar ADR-037, avaliar quota e ponte, custo e replay |
| Alto | Recuperação de mensagens após 24h de DynamoDB Streams não foi testada | Testar conciliação diária, republicador e trilha S3/Athena |
| Médio | Validação real Terraform sem `terraform` disponível neste ambiente | Rodar `terraform fmt -check`, `terraform validate` e `plan` local/prod sem apply |
| Médio | LocalStack 4.0 e Lambdas Java nunca executados nesta auditoria | Rodar Compose do zero no Mac; guardar logs e respostas |
| Médio | Segurança pública/privada, quotas, configuração Cognito/WAF/ECS/ALB | Revisão `terraform plan` e smoke test em conta de sandbox autorizada |
| Médio | Custos calculados por modelo, 5.000 TPS de pico não testados | Benchmark k6 em ambiente isolado e orçamento revisado |

## Regras
- Sem GitHub Actions.
- Sem AWS `terraform apply` ou custos.
- Sem afirmar sucesso de testes Maven/Docker que não foram executados.
