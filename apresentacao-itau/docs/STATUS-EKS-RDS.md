# Checklist de implementação EKS/RDS PostgreSQL — 22/10/2026

**Sem GitHub Actions; sem aplicar AWS.**

## Situação atual consolidada — revisão LocalStack de 22/10/2026

- Branch `codex/localstack-readiness` integrada à main: `25f826f` sobre `052971f`.
- Validação independente: **142 testes unitários Java + 9 IT, zero falhas/erros/ignorados**.
- Seis testes Python, verificadores estáticos, preflight, Compose config e Bash: aprovados.
- Terraform local: fmt, init -backend=false -lockfile=readonly e validate aprovados.
- Roteiro LocalStack implementado em `local/executar-tudo.sh`, com aceite automático.
- **E2E completo Docker/LocalStack ainda não executado nesta revisão**; executar no Mac.
- Fases 2–4 e 6–7 de produção continuam abertas. O aceite local não as encerra.
- ADR-046 registra decisões e limites; `COMO-RODAR.md` é o roteiro atual de execução.

Os registros abaixo preservam a cronologia; declarações antigas de "pendente" ou "parcial"
são superadas apenas pelas evidências explicitamente registradas após cada revisão.


| Pri. | Requisito | Status no Git | Aceite obrigatório |
|---|---|---|---|
| P0 | Operações financeiras com reserva por ID | Código e testes criados | Maven/Testcontainers: CAPTURA, LIBERACAO e expiração concorrentes |
| P0 | Contrato OpenAPI/DTO seis operações | Código atualizado | HTTP 201/422 e contrato sincronizados |
| P0 | Conta pertence ao contrato | Código | Teste E2E conta divergente |
| P0 | Cliente autenticado autorizado em contrato | DynamoDB entitlement fail-closed | Provisionamento corporativo, revogação e teste cruzado 403 |
| P0 | EKS substitui ECS em prod | Módulo EKS + manifests | Terraform plan EKS Auto Mode sa-east-1, NLB TG binding |
| P0 | RDS PostgreSQL substitui Aurora | `aws_db_instance` Multi-AZ | terraform plan, conexão TLS/JDBC, restauração backup |
| P0 | Conciliação sem Data API Aurora | Lambda JDBC + Step Functions | Exercitar export AWS RDS `aws_s3` end-to-end |
| P0 | Segurança de banco: sem app admin | Manifesto runtime com `contabilizacao_app` e Flyway desativado; runbook criado | Provisionar DB role/segredo, aplicar migrações com usuário DDL e provar conexão |
| P0 | K8s Secrets e IAM | Pod Identity codificada | Pod consegue acessar Dynamo/Kinesis/SQS sem chaves estáticas |
| P0 | JWT e zero trust interno | JWT de entrada implementado; identidade interna pendente | validação token downstream, policy namespace/egress/TLS |
| P1 | Cluster privado e PrivateLink | Endpoints ec2/eks-auth/ecr/s3/logs/... | Testar bootstrap de nodes sem NAT |
| P1 | Autoscaling | HPA + EKS Auto Mode; instalação de metrics-server não localizada | Teste escala 5.000 TPS, backpressure Kinesis e p99 |
| P1 | Durabilidade RDS | Multi-AZ+backups+proteção exclusão | PITR/restore e RPO/RTO medidos |
| P1 | Capacidade/retention contábil bilhões/mês | **Não dimensionada** | Benchmark writer, particionamento e arquivamento |
| P1 | Expiração em alta escala | Scheduler por reserva | Dimensionar quota reservas ativas e custo |
| P1 | Observabilidade EKS | logs control plane, Java telemetry | Deploy ADOT e alarmes workload/HTTP/DB |
| P1 | Outbox/replay após TTL | Manual histórico | Ensaiar retentativas/DLQ e recuperação durável |
| P1 | EventBridge transações | Pipes opcional OFF | Avaliar quotas/custos e execução |
| P2 | Diagramas AWS e custos EKS/RDS | Docs Markdown atualizados | PNG AWS official icons, PRICING CALCULATOR na região |
| P2 | RTO/RPO e DR | Documentado | Exercício regional e runbook validado |

## Testes no Mac (roteiro atual em COMO-RODAR.md)

```bash
cd case-aws-finance/apresentacao-itau
python3 local/verificar-estatico.py
mvn clean verify
mvn verify -Pintegracao
terraform -chdir=infra/terraform/ambientes/prod init -backend=false
terraform -chdir=infra/terraform/ambientes/prod validate
# NÃO EXECUTAR terraform apply no prod.
# Demo local usa Docker+LocalStack+PostgreSQL container (não EKS gerenciado)
bash local/subir.sh
bash local/demo.sh
bash local/derrubar.sh
```

O cluster EKS real só pode ser validado com conta AWS previamente autorizada,
e `k8s/render.py` gera YAML mas não aplica. Para simular Kubernetes local,
precisa de kind/k3d e adaptações do template para não usar AWS TargetGroupBinding/Pod Identity.

## Revisão da Fase 0 — 22/10/2026

**Parcial. Não há aprovação de compilação, provider Terraform, LocalStack ou AWS.**
Baseline local igual à `main` consultada no GitHub: `87551fe9b71ceb611a44d57b77fec4e3f2832ed8`.
Histórico recuperado sem alterar arquivos de trabalho; diff `ad35be77..HEAD` inspecionado.

### Correções e arquivos

- `infra/terraform/modulos/dados/{main,variables}.tf`: atributos separados por ponto e vírgula e blocos multilinha inválidos corrigidos.
- `infra/terraform/modulos/eks/{main,variables,outputs}.tf`: encryption_configuration, egress, health_check, default_action, variáveis e output sensível com sintaxe válida.
- `infra/terraform/ambientes/prod/outputs.tf`: output sensível corrigido.
- Somente formatação adicional em `ambientes/prod/{main,variables}.tf`, `modulos/dados/outputs.tf`, `modulos/conciliacao/{main,variables}.tf`, `modulos/eventos/{main,outputs}.tf`, `modulos/funcoes/{main,variables}.tf`.
- `scripts/verificar-migracao-eks-rds.py`: checagem de atributos booleanos tolerante ao alinhamento do formatter; continua sendo inspeção textual, não parser HCL.
- `PENDENCIAS-CODEX.md`, este status e `docs/evidencias/fase-0-2026-10-22.txt`: rastreabilidade.

### Evidências efetivamente executadas

[Log dos comandos e códigos de saída](evidencias/fase-0-2026-10-22.txt).

| Verificação | Resultado |
|---|---|
| Verificador migração Python | 11 controles passaram |
| Verificador estático local | Passou; sintaxe/estrutura apenas |
| Terraform fmt recursivo | Inicialmente falhou por HCL inválido; após correção, exit 0 |
| Terraform init -backend=false | Falhou: CLI 1.5.7, requisito >=1.9 |
| Terraform validate | Falhou: pacote AWS 5.100.0 não instalado |
| Maven clean verify / perfil integracao | Não executados: mvn ausente; Java disponível 17.0.6, projeto requer 21 |
| Actions / deploy / plan / apply / E2E | Nenhum executado; workflows ausentes |

### Inventário e riscos classificados

- `compute/` já está identificado como histórico e não é chamado por prod. Não removido.
- O lockfile de prod fixa AWS 5.100.0 / ~>5.80, enquanto main.tf exige >=6.15,<7.0. **Bloqueio P0:** regenerar com CLI compatível e revisar diff/checksums, sem editar hashes manualmente. Suporte dos recursos e dependências ainda não validado.
- O checkout esparso inclui apenas `apresentacao-itau` e arquivos da raiz; `docs/MEMORIA.md` foi lido via `git show`. Ele ainda descreve ECS/Aurora e aprovações antigas: não constitui evidência da migração atual. Diagramas, custos e documentos históricos permanecem para a Fase 7.
- PDF original ausente na árvore Git; busca em Documents/consultoria não encontrou PDF. Aderência direta ao enunciado continua pendente, sem inventar requisitos.
- ADRs 026–031 estão no catálogo; 032–035 aparecem como conjunto ainda sem catalogação completa. Não declarar leitura de documentos inexistentes.
- Referências de wiring de entitlement e exportador JDBC existem; não foram removidas classes como supostamente mortas sem compilação/análise completa. Revisão exaustiva de classes e contratos permanece pendente.
- `aurora_exportacao` ainda nomeia a role IAM histórica da conciliação, embora a associação seja RDS convencional. Classificado para Fase 3; renomear exige avaliar estado/migração.
- Autenticação interna, permissões SQL/TLS, consistência financeira e capacidade permanecem nos respectivos P0/P1 já listados. Nenhuma aprovação operacional inferida do formatter.

### Retomada no Mac após disponibilizar ferramentas

Com JDK 21, Maven e Terraform >=1.9, na pasta `apresentacao-itau`:

```bash
java -version
mvn -version
mvn clean verify
# Exige Docker para Testcontainers; executar posteriormente conforme combinado:
mvn verify -Pintegracao
terraform -chdir=infra/terraform fmt -check -recursive
# -upgrade necessário para resolver o lockfile antigo; sem backend, plan ou apply:
terraform -chdir=infra/terraform/ambientes/prod init -backend=false -upgrade
terraform -chdir=infra/terraform/ambientes/prod validate
git diff -- infra/terraform/ambientes/prod/.terraform.lock.hcl
```

Anexar resultados e corrigir novos erros antes de encerrar a Fase 0. Compilação e validação do provider
não foram substituídas por revisão estática. Commit da correção registrado abaixo após criação; sem push.

Commit das correções e evidências da Fase 0: `d72c1075702929e226115867196b3607b8f445ba` (local, sem push).

## Revisão de entrega com PDF — 22/10/2026

PDF original localizado em Downloads e lido integralmente (5 páginas). Parecer: **ainda não pronto para entrega final**. Demonstração Docker é aceita pelo §5; Aurora não é obrigatório. Revisão estática/documental, sem novos testes de runtime. Ver [relatório](REVISAO-PRONTIDAO-ENTREGA.md) para evidências, seis pilares, prioridades e critérios de liberação. Fase 0 permanece parcial.

Os commits `d72c107` e `e2563e0` foram enviados a origin/main após o registro anterior; a anotação “sem push” acima é histórica.

## Progresso Fase 0 — build e Terraform executados

Em 22/10/2026, `mvn clean verify` passou com JDK 21/Maven 3.9.9 em cópia temporária do baseline `607fd65`; Terraform 1.9.8 inicializou e validou prod (com avisos de atributo deprecated) e local. Quatro testes do importador OpenAPI passaram. Evidência: `docs/evidencias/fase-0-build-2026-10-22.txt` dentro de apresentacao-itau.

Alterados: lockfiles prod/local (AWS prod 6.70.0; checksum local), teste OpenAPI que esperava versão antiga, teste IT para falhar sem Docker e novo `local/verificar-build.sh`. Teste IT alterado ainda pendente de execução; demo E2E, segurança, capacidade e demais fases não concluídas. Commit de progresso solicitado pelo usuário, sem declarar encerramento da fase.

### Fechamento da validação da Fase 0

Commit de implementação `0e0fede` enviado ao remoto. `mvn verify -Pintegracao` passou: 6 testes DynamoDB Local, zero falhas/erros/ignorados, além da suíte unitária. Nenhum E2E completo ou teste AWS está aprovado. Build em /tmp evita lentidão de leitura no workspace; fontes extraídas do Git e o teste IT atualizado copiado antes da execução. Erros conhecidos da Fase 0 resolvidos; avisos do provider e catalogação histórica registrados. Prosseguir sequencialmente à Fase 1.

## Fase 1 — identidade e integridade financeira (22/10/2026)

Implementados JWT RS256 no backend com issuer/validade/token_use/client_id/scope, rejeição de identidade forjada por header, entitlement com validade explícita e leitura forte sem cache. Expiração agora confere o prazo e usa chave idempotente por versão; condições de compensação por reserva corrigidas. Incluídos testes de segurança, revogação/acesso cruzado, expiração antecipada/concorrente e três novos IT financeiros (9 IT passaram). Suíte e evidências: `docs/evidencias/fase-1-2026-10-22.txt`.

Arquivos: configuração/DTO HTTP/testes de Autorização, adaptador/testes DynamoDB de Limites, pom, contratos HTTP/entitlement, renderer/manifests, scripts/Compose locais e ADR-045. Seed local passou a usar JWT assinado e validade; chaves efêmeras ignoradas no Git, portas restritas ao loopback.

Riscos/dependências: ingestão da fonte corporativa de entitlement depende do contrato do banco (desenho versionado na ADR-045; não implantado); integração Cognito real/JWKS no cluster privado e E2E local pendentes. Cognito existente autentica canais M2M, não 80 milhões de contas/usuários. Segurança leste-oeste e TLS RDS continuam nas fases seguintes. A conclusão dos testes não é aprovação de produção.

## Revisão incremental Fase 1 — contrato HTTP (22/10/2026)

Baseline `052971f`. Corrigidos: campo desconhecido rejeitado pelo Jackson conforme
`additionalProperties: false`; `metadata.id_autorizacao_original` exige string UUID
canônica; schema explicita múltiplos de centavo; respostas 401/403 do filtro incluem
`codigo`; OpenAPI possui exemplos das seis operações. ADRs 042–044 reconciliadas
com as evidências anteriores. Acrescentados três testes HTTP de regressão.

Validações desta revisão: verificadores Python EKS/RDS e estático local passaram.
Build Java e E2E desta revisão serão registrados separadamente; os testes anteriores
não atestam automaticamente o código alterado. Checklist amplo da Fase 1 permanece
aberto para os cenários ainda sem evidência (timeout após commit, mudanças de limite,
integração corporativa). Não há fechamento da fase nem aprovação de produção.

Por instrução atual do usuário, a próxima etapa é preparar e corrigir a execução local
com LocalStack (subfase local da Fase 5), mantendo os P0 de produção visíveis. Isso
não muda a arquitetura e não declara concluídas as Fases 2–4.

## Revisão do setup LocalStack — 22/10/2026

Preparados `local/verificar-infra.py` e regressões: prontidão real dos recursos,
falha imediata para Lambda Failed, diagnóstico automático de containers, versões
mínimas e lockfile readonly. O estado local só é removido quando DynamoDB retorna
ResourceNotFoundException, sem confundir falha de conexão com emulador recriado.

Validações executadas nesta revisão: seis testes Python aprovados, verificadores
estáticos EKS/RDS e local aprovados, bash -n e git diff --check aprovados.
Sem Docker/Maven/Terraform instalados neste host: não houve novo build Java,
validate Terraform ou E2E. Scripts preparados para Docker Desktop no Mac.
Arquivos adicionais: `local/{verificar-infra.py,test_verificar_infra.py,preflight.py,
subir.sh,executar-tudo.sh}`, COMO-RODAR e ADR-046. Commit: consultar histórico
`25f826f` (commit consolidado publicado da branch LocalStack).


## Integração das branches — 22/10/2026

A consulta `git ls-remote --heads origin` encontrou `main` (`052971f`) e
`codex/localstack-readiness` (`25f826f82b4805540de07f5f60232f388009f047`).
A branch contém um commit descendente da main; integrada por fast-forward, sem
conflitos ou descarte de alterações. Foram revisados os 32 arquivos do commit:
setup/aceite local, Compose, contratos HTTP, validação Java e documentação.
O SHA intermediário `50df502` citado na documentação recebida não consta do
histórico publicado; essas alterações estão consolidadas em `25f826f`.

Validação independente do commit integrado: **142 testes unitários Java + 9 testes
DynamoDB Local/Testcontainers, sem falhas/erros/ignorados**, seis testes Python,
verificadores estáticos, preflight, Compose config, sintaxe Bash e Terraform fmt.
Terraform 1.9.8: init local com backend desativado e lockfile readonly, seguido de
validate, passaram. Evidência: `apresentacao-itau/docs/evidencias/integracao-branches-2026-10-22.txt`
(caminho relativo à raiz do repositório).

Esta revisão não executou o E2E completo nem apply/deploy AWS. Para o aceite no Mac:
`cd apresentacao-itau && INTEGRACAO=1 bash local/executar-tudo.sh`.
Pré-requisitos em COMO-RODAR; logs em local/evidencias (ignorados pelo Git).
Permanecem os riscos de produção já registrados (identidade interna/TLS, integração
corporativa, recuperação, capacidade/custos e operação EKS/RDS). A integração não
encerra essas fases. Arquivos adicionais desta revisão: backlog, STATUS e evidência.
