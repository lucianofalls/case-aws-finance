# Relatório final da auditoria — Itaú POD99 / Claude Code

**Data:** 21/10/2026  
**Repositório:** `lucianofalls/case-aws-finance` · branch `main`  
**Fonte:** `itau-pod99-solucao(2).zip` (entregue pelo usuário) e requisitos de `Desafio Técnico — Arquiteto de Aplicação` (5 páginas)  
**Escopo:** revisão e correções incrementais, sem execução AWS, GitHub Actions, Maven ou Terraform  
**Avaliação:** **apto a prosseguir para validação de integração e defesa, NÃO apto a produção**.

## 1. Evidência de importação e preservação

- ZIP original: **244 arquivos**; **244/244 hashes Git SHA-1 idênticos** depois da importação. Inclui Java, testes, Terraform, docs, PNG e SVG.
- **Commit base intocado:** [ad35be77](https://github.com/lucianofalls/case-aws-finance/commit/ad35be77a0293824a610f18b72c10e71af6acc07).
- Documentação e correções foram adicionadas em commits posteriores, preservando a referência exata da versão de Claude Code.
- Nenhum workflow `.github/workflows` foi publicado ou executado.

## 2. A solução entregue

| Domínio / responsabilidade BIAN de referência | Bounded Context / implementação | Runtime | Banco e integridade |
|---|---|---|---|
| Transaction Authorization | Autorização (`servicos/autorizacao-service`) | ECS Fargate (Java 21/Spring Boot) | DynamoDB autorizações, id determinístico, Outbox embutida |
| Position Keeping; Limit and Exposure Management (aspectos) | Limites (`servicos/limites-service`) | ECS Fargate | DynamoDB limites, `TransactWriteItems`, posição e operação |
| Financial Accounting | Contabilização (`servicos/contabilizacao-service`) | ECS Fargate | Aurora PostgreSQL (local: Postgres), Inbox e partidas dobradas |
| Core Banking, produtos legados (ACL) | `funcoes/corebanking-acl` | AWS Lambda | Tradução de contratos, publicação `LimiteConcedido` |
| Integração/reconciliação | Lambdas relay e reconciliador | Lambda/Step Functions | Streams → Kinesis/EventBridge; conciliação S3/Athena/Aurora |

**Arquitetura:** API Gateway REST/WAF/Cognito → NLB privado → Autorização → Limites; eventos via DynamoDB Streams e Lambda relay; alto volume em **Kinesis** e integração de limites/core em **EventBridge**; SQS para verificação e integrações. Terraform separado entre `infra/terraform/ambientes/local` e `prod`.

**Trade-off importante:** Autorização e Limites são dois microsserviços e confirmam em bancos próprios. Não existe transação ACID distribuída; a solução depende de compensação, idempotência e conciliação para resultados incertos. A escolha é defensável, mas requer ensaio específico de falhas.

## 3. O que foi corrigido

| Fase | Alteração | Evidência |
|---|---|---|
| Importação | 244 arquivos preservados integralmente, incluindo dois PNG | SHA Git comparado ao conteúdo do ZIP |
| Financeira | Resposta desconhecida da reversão no Limites passa a ser erro; a mensagem não deve receber ACK indevido | `VerificarAutorizacaoIncerta.java` + testes |
| Conciliação | Consulta de órfãs em Athena substitui `NOT IN` por `NOT EXISTS` para não suprimir resultados por valores nulos | `JanelaDeConciliacao.java` + teste |
| SQS | Corpo da fila de verificação serializado por Jackson, não concatenação manual de JSON | `SqsFilaDeVerificacao.java` + novo teste |
| Usabilidade | Scripts `subir.sh`, `demo.sh`, `derrubar.sh`, `verificar-contrato-api.sh` e `autorizacao.js` sem sufixo `.txt` | Git renames em `apresentacao-itau` |
| Segurança local | Portas do Docker Compose limitadas a `127.0.0.1` | `local/docker-compose.yml` |
| Validação offline | `python3 local/verificar-estatico.py` criado e executado | Resultados reproduzíveis abaixo |
| ADRs | 036 (compensação), 037 (EventBridge/Kinesis), 038 (teste local) | `apresentacao-itau/docs/adr/` |

Não foi alterada a modelagem original por preferência tecnológica: permanecem ECS Fargate, DynamoDB por bounded context, Aurora na contabilidade e Kinesis para fluxo transacional.

## 4. Validações efetivamente executadas

**Aprovado nesta revisão:**
- Conferência **244/244 SHA Git** da importação.
- Parse de **8 JSON**, **3 YAML + 4 YML**, **10 XML**, **2 SVG**, **4 Python**, **4 Bash** (sintaxe) e assinatura de **2 PNG** na árvore normalizada.
- Compilação isolada em **Java 21** de `VerificarAutorizacaoIncerta.java` e `JanelaDeConciliacao.java` (sem dependências externas).
- Verificação estática da estrutura principal e das extensões dos scripts.
- Confirmação de que o repositório **não contém workflows GitHub Actions**.

**NÃO executado nesta revisão:**
- Maven `test`/`verify`, JUnit/ArchUnit/Testcontainers (Maven não disponível no ambiente).
- Docker Compose/LocalStack/PostgreSQL/Jaeger end-to-end (Docker indisponível).
- `terraform fmt`, `terraform validate`, `terraform plan` (Terraform indisponível).
- Deploy em AWS, testes reais de IAM/segurança e benchmark de 5.000 TPS.
- Verificação do custo AWS com medição real.

Arquivos e testes presentes **não equivalem a testes aprovados**.

## 5. Matriz de aderência ao PDF do Itaú

| Item do desafio | Evidência do ZIP | Parecer |
|---|---|---|
| DDD: BCs, linguagem ubíqua, agregados e invariantes | Autorização/Limites/Contabilização, docs domínio | **Estruturado**; testes não executados nesta revisão |
| SOLID, Clean Architecture, DIP | `domain/application/adapters/config`, testes ArchUnit | **Estruturado**; validar build |
| API contract-first OpenAPI 3.x, erros HTTP, UUID idempotência | OpenAPI externo/interno, controllers, Terraform API REST | **Estruturado**; smoke E2E pendente |
| Multi-conta/contratos, 80 milhões, picos 5.000 TPS | Modelo key-value, Terraform Kinesis/ECS, k6 | **Dimensionamento hipotético**, não comprovado |
| Consistência sob concorrência por contrato | Operação + posição DynamoDB em transação e optimistic version | **Implementado localmente**; testes integrados pendentes |
| Persistência por serviço | DynamoDB Autorizações, DynamoDB Limites, Aurora Contabilização | **Atendido no desenho** |
| ACL de core banking | Lambda `corebanking-acl`, tradutor e consumo de `LimiteConcedido` | **Adapter/stub**, não core real |
| CloudEvents, versionamento, schema registry | Published Language, AsyncAPI, JSON Schemas, Glue | **Estruturado**; sem prova de integração AWS |
| EventBridge + regras, SNS/SQS para fan-out | EventBridge/SQS para limites; SNS usado para alarmes | **Parcial no negócio**; SNS→SQS de notificações não demonstrado |
| `TransacaoAutorizada` v1 publicado no EventBridge (PDF §2.4) | Publicado no **Kinesis**, não no bus EventBridge | **Divergência explícita — ADR-037 aberta** |
| Avaliação SQS/Kinesis/MSK | ADR-028 e módulos de eventos | **Documentado**; Kinesis não garante ordem com `PutRecords` |
| AWS Well-Architected e trade-offs | ADRs, seis pilares, Terraform modular, custos | **Bem documentado**; práticas operacionais não medidas |
| Teste local e defesa de 20 + 40 min | `local/`, `COMO-RODAR.md`, roteiro e diagramas | **Preparado**, execução não comprovada |

## 6. Riscos abertos antes de fechar o case

| Severidade | Item | Bloqueio/critério de aceite |
|---|---|---|
| **ALTO — R1** | Dois commits independentes Limites→Autorização | Testar timeout após commit do Limites, resposta 503, ausência de decisão, agendamento em SQS, reversão depois de 15 min e corrida com idempotência até 10 min |
| **ALTO — R2** | Exemplo obrigatório `TransacaoAutorizada` no EventBridge | Decidir ADR-037: ponte com quotas/custo/replay comprovados ou justificativa formal de exceção |
| **ALTO — R3** | Streams perde registros após ~24h | Exercitar reconciliação diária já existente (Scheduler/Step Functions/exports/Athena/republicador) e recuperação de evento antigo |
| **ALTO — R4** | Contabilidade, antifraude, notificações e ACL dependem de integrações simuladas | Executar demo e comprovar Inbox/razão, DLQ, idempotência e interfaces do core |
| **MÉDIO — R5** | SNS de negócio não é usado para fan-out | Demonstrar SNS→SQS ou justificar arquitetura alternativa no item 3.3 |
| **MÉDIO — R6** | Terraform não foi validado contra providers atuais | `terraform fmt -check`, `validate`, `plan` com review de IAM/segurança sem `apply` |
| **MÉDIO — R7** | Quotas, custo e latência sob pico | k6 + métricas p95/p99 + hot keys por contrato, não usar cenário de 1.500 TPS médios como requisito do PDF |
| **MÉDIO — R8** | TTL de 35 dias e S3 Object Lock | Confirmar retenção/auditoria e políticas bancárias exigidas; evitar afirmar conformidade regulatória sem aprovação |
| **MÉDIO — R9** | LocalStack 4.0 fixado; runtime Lambda/serviços locais | Subir do zero no Mac Apple Silicon e guardar resultados; emulação não equivale AWS |
| **MÉDIO — R10** | Contrato verificado por `oasdiff` apenas como script | Executar manualmente a verificação antes da defesa; não usar GitHub Actions |

**Correções já resolvidas:** desconhecimento de status de reversão, `NOT IN` com `NULL`, JSON de SQS malformável, nomes de scripts e exposição local de portas.

## 7. Checklist sugerido para conclusão

1. No Mac/VS Code: `python3 local/verificar-estatico.py`; `mvn -B verify` e `mvn -B verify -Pintegracao`.
2. Inspecionar `infra/terraform/ambientes/local/main.tf` e confirmar que todos os endpoints apontam para LocalStack. Depois, `bash local/subir.sh` (**faz Terraform apply somente no ambiente local**).
3. `bash local/demo.sh`: guardar HTTP e logs, eventos Kinesis, linhas de partidas dobradas no PostgreSQL e traces Jaeger.
4. Injetar falhas: matar Autorização após commit em Limites; derrubar relay; testar SQS DLQ e reconciliação; evidenciar retorno ao estado financeiro correto.
5. Decidir e documentar ADR-037 para cumprir literalidade do exemplo EventBridge e revisar uso SNS/SQS de negócio.
6. Validar Terraform de produção com `init -backend=false`, `validate` e `plan` em sandbox **sem aplicar**; revisar custos e ameaças.
7. Em ambiente autorizado e isolado, executar k6 com distribuição de contratos, concorrência por chave quente, latência p95/p99 e 5.000 TPS de pico.
8. Congelar versões/ADRs e fazer a apresentação em 20 minutos com demos e respostas aos principais trade-offs.

## 8. Recomendação

**Prosseguir com a base do Claude Code.** A solução apresenta separação de domínios, bons mecanismos de mitigação para uma mini-saga, reconciliação diária, Outbox e persistência adequada a cada serviço. Não recomendo reescrever tudo em um único banco ou migrar de Fargate para EKS sem necessidade.

Para uma entrega de entrevista, a prioridade imediata é **obter evidência de execução local íntegra** e fechar o gap do evento EventBridge. Para produção bancária, ainda faltam validação de requisitos operacionais, segurança, custos, SLO e testes de recuperação.

**Esta auditoria não ativou GitHub Actions e não criou recursos AWS.**
