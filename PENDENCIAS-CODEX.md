# Backlog para o Codex — conclusão do case Itaú POD99

**Estado de referência:** 22/10/2026, branch `main`, arquitetura-alvo **EKS Auto Mode + RDS PostgreSQL Multi-AZ convencional (não Aurora)**, DynamoDB para Autorização/Limites, Kinesis/EventBridge para eventos.  
**Objetivo:** completar código, IaC, segurança, documentação e preparação dos testes locais, mantendo conformidade com o PDF original do Itaú e os **seis pilares AWS Well-Architected / Financial Services Industry Lens**.

> **Este arquivo é uma lista de trabalho, não um atestado de validação.** Existe código/IaC das fases anteriores; não reimplementar sem verificar. O usuário executará os testes completos no Mac posteriormente.

## Restrições obrigatórias

- **NÃO** criar, habilitar ou executar GitHub Actions; a pasta `.github/workflows/` deve permanecer ausente.
- **NÃO** rodar `terraform apply` contra AWS, criar recursos, fazer deploy no EKS, usar credenciais reais, publicar imagens ECR ou causar cobrança.
- **NÃO** modificar a arquitetura escolhida para ECS/Aurora: **EKS Auto Mode e RDS PostgreSQL Multi-AZ** são requisitos.
- **NÃO** publicar senhas, tokens, segredos, estados Terraform, logs com credenciais ou arquivos `.env`.
- Preservar `ad35be77` como marco da importação integral do ZIP Claude Code; não usar force-push.
- Fazer **uma fase por vez**, commits pequenos, atualizando a tabela de status depois de cada fase. Reportar arquivos alterados e critérios de aceite. Não afirmar que compilou, passou em Terraform ou executou AWS sem evidência.
- Se alguma ferramenta não estiver disponível, concluir a revisão estática possível e registrar comando/resultado como **pendente de execução pelo usuário**.

## Já existe no repositório — preservar e verificar

- Três bounded contexts Java 21: Autorização, Limites e Contabilização, DDD + portas/adaptadores.
- Padrões de idempotência, DynamoDB ACID, Outbox, mini-saga, verificação em SQS e conciliação.
- Proteção por reserva individual em DynamoDB e verificação conta/contrato, com novos testes (não considerados executados).
- Projeção de entitlement `cliente → contrato → conta`, *fail-closed*, ainda sem fonte corporativa de provisionamento/revogação.
- Módulo Terraform `infra/terraform/modulos/eks`, manifests `k8s/`, EKS Pod Identity e NLB privado.
- Módulo `dados` com RDS PostgreSQL Multi-AZ; conciliação adaptada para Lambda JDBC, sem Aurora Data API.
- EventBridge Pipes transacional **opcional e OFF por padrão**; Kinesis continua o caminho principal.
- ADRs 042–044, `docs/visao-eks-rds.md`, `docs/STATUS-EKS-RDS.md`, guia `k8s/README.md` e `scripts/verificar-migracao-eks-rds.py`.

## Fase 0 — Congelar baseline e verificar incoerências [P0]

- [x] Ler o **PDF original do Itaú**, `MIGRACAO-EKS-RDS.md`, `apresentacao-itau/docs/visao-eks-rds.md`, `STATUS-EKS-RDS.md`, ADRs 026–044 e a implementação. Não substituir fatos do enunciado por hipóteses.
- [x] Conferir árvore real e diffs desde a importação; identificar código inacabado, classes não referenciadas, Terraform `compute/` histórico e documentos ECS/Aurora antigos.
- [x] Validar estaticamente `python3 scripts/verificar-migracao-eks-rds.py` e `python3 local/verificar-estatico.py`; corrigir erros verificáveis. Evidência: `apresentacao-itau/docs/evidencias/fase-0-2026-10-22.txt`.
- [x] Revisar `mvn clean verify` e `mvn verify -Pintegracao` no ambiente Codex **somente se disponíveis**; preparar instruções ao usuário caso dependam do Docker Mac.
- [x] Validar `terraform fmt -check -recursive`, `terraform init -backend=false`, `terraform validate` em `infra/terraform/ambientes/prod` (sem `plan/apply` com credenciais reais). Corrigir erros de HCL, providers, interpolação, tipos, endpoints e relações de dependência.
- **Aceite:** erros conhecidos resolvidos ou classificados em `docs/STATUS-EKS-RDS.md` com evidências reais e pendências explícitas.

## Fase 1 — Integridade e autorização financeira [P0]

- [ ] Conferir e alinhar **OpenAPI, JSON Schema, DTO, domínio, tratamento HTTP e exemplos** das seis operações `DEBITO/CREDITO/RESERVA/CAPTURA/LIBERACAO/ESTORNO`, campos `snake_case`, retornos 201/200/402/409/422/429/503 e `Idempotency-Key`.
- [ ] Garantir vínculo `id_cliente` autenticado → `id_contrato` → `id_conta`, com **negação por padrão**; garantir que chamadas internas/headers não permitam falsificação de identidade.
- [ ] Completar desenho de **provisionamento e revogação corporativa dos entitlements**, estratégia de consistência e TTL/cache, com contrato e teste para revogação e cross-tenant. Não presumir que a projeção DynamoDB é a fonte original.
- [ ] Conferir transações DynamoDB de **posição + operação idempotente + reserva individual**; prevenir captura/liberação acima do saldo específico, não só do total bloqueado.
- [ ] Revisar concorrência entre `CAPTURA`, `LIBERACAO`, `ESTORNO` e **expiração**; produzir testes determinísticos de movimentos parciais, duplicidade, mesma chave com payload diferente, timeout após commit e retries.
- [ ] Garantir invariantes `total = disponível + bloqueado + utilizado` nas condições de negócio aplicáveis; tratar corretamente mudanças do limite concedido abaixo do utilizado (conforme ADR).
- **Aceite:** casos de domínio cobertos, contratos sincronizados e nenhum movimento financeiro sem titularidade/entitlement ou saldo de reserva suficiente.

## Fase 2 — EKS Auto Mode, Kubernetes e entrada da API [P0]

- [ ] Revisar `infra/terraform/modulos/eks`, `ambientes/prod`, `k8s/templates/stack.yaml.tpl`, `k8s/render.py` para **EKS Auto Mode em 3 AZs**, namespace, deployments, services, HPA, PDB, readiness/liveness, NLB e `TargetGroupBinding`.
- [ ] Conferir suporte **real** na versão/região escolhida para EKS Auto Mode, CRDs e API version `TargetGroupBinding`; não supor compatibilidade somente porque os YAMLs foram renderizados.
- [ ] Validar cadeia **API Gateway REST → VPC Link → NLB interno → Pod de Autorização** e caminho restrito do reconciliador para Limites, incluindo roteamento, health-check, headers de identidade e SGs.
- [ ] Conferir **Pod Identity** por ServiceAccount, trust policy, IAM mínima e dependências de bootstrap privado: VPC endpoints `eks-auth`, `ec2`, `eks`, ECR, STS, SQS, Kinesis, CloudWatch, Secrets Manager etc.; não assumir NAT.
- [ ] Completar RBAC, políticas Pod Security, restrição de criação de TargetGroupBinding, egress/ingress via **NetworkPolicy validável** e separação efetiva de workloads.
- [ ] Definir e implementar **autenticação e TLS no tráfego leste-oeste** entre Autorização/Limites (JWT de workload e/ou mTLS), mantendo o fluxo funcional; documentar a gestão de certificados e rotação.
- [ ] Preparar observabilidade dos Pods e **Metrics Server** necessário para HPA, sem afirmar implantação; validar logs, tracing, métricas, timeouts, graceful shutdown e scale-in seguro do KCL.
- **Aceite:** Terraform e YAML com caminhos coerentes, sem referências ativas a ECS na produção, e segurança interna sem confiança exclusiva em HTTP/header.

## Fase 3 — RDS PostgreSQL padrão, segurança SQL e conciliação [P0]

- [ ] Garantir **RDS PostgreSQL convencional**: `aws_db_instance` Multi-AZ, subnet privada, KMS, backups/PITR, proteção contra exclusão, storage `gp3`; **nenhum cluster Aurora ou Data API no caminho ativo**.
- [ ] Separar contas **DBA/migração**, **runtime `contabilizacao_app`** e **exportação/reconciliação**, com GRANT mínimo e segredos independentes. Não usar credencial master nos Pods ou Lambda de exportação.
- [ ] Validar TLS PostgreSQL `sslmode=verify-full` com CA e hostname; desenhar rotação Secrets Manager → Kubernetes (External Secrets ou solução corporativa), sem credenciais em Git.
- [ ] Inspecionar Lambda JDBC de exportação e Step Functions: acesso privado ao banco, RDS role para `aws_s3.query_export_to_s3`, permissões S3/KMS, transações, queries Athena/Glue e IAM `lambda:InvokeFunction`.
- [ ] Identificar quaisquer referências persistentes a `module.dados.aurora`, `rds-data:ExecuteStatement`, `aws_rds_cluster`, `aurora-iopt1` ou usuário `contabilizacao_admin` no **caminho ativo**; corrigir sem apagar documentação histórica sem aviso.
- [ ] Planejar **particionamento do ledger e Inbox**, retenção, índices, arquivamento S3 e limite de conexões/HikariCP. No cenário hipotético de 1.500 TPS médios, registrar por que milhões/bilhões de linhas não cabem em dimensionamento não comprovado.
- **Aceite:** migração SQL executável com papel adequado; plano de backup/failover; conciliação sem Aurora e backlog explícito de capacidade.

## Fase 4 — Event-driven, consistência e recuperação [P1]

- [ ] Provar por testes de falhas o ciclo **DynamoDB Streams → Lambda relay → Kinesis → KCL → Inbox + partidas dobradas no PostgreSQL**, idempotência e reordenação.
- [ ] Verificar saga em que Limites confirma e Autorização falha; SQS atrasada, DLQs, retries e reversão **sem retirar limite de outra operação**.
- [ ] Revisar expiração via EventBridge Scheduler em grande volume: quota de agendamentos, limpeza/cancelamento quando reserva termina, target retry/DLQ, alternativas batch, custos.
- [ ] Validar **quarentena, reprocessamento, DLQ** e que tipos novos/não reconhecidos não são descartados silenciosamente.
- [ ] Resolver estratégia de **replay após 24h de Streams e TTL de 35 dias do DynamoDB**; runbook manual não é recuperação automática. Identificar fontes independentes confiáveis mesmo para evento jamais entregue ao Kinesis.
- [ ] Manter **Kinesis como caminho principal** e decidir com evidências a ponte opcional **Kinesis → EventBridge Pipes → SNS/SQS** (desligada por padrão), diante do exemplo `TransacaoAutorizada` no EventBridge no PDF. Não ativar sem quotas/custo.
- **Aceite:** eventos sem perda silenciosa, métricas de lacunas e ADR com limites de entrega/reprocessamento.

## Fase 5 — Testes locais para executar no Mac [P1]

- [x] Atualizar `local/subir.sh`, `local/demo.sh`, `local/demo-ciclo-reserva.sh`, Docker Compose e instruções para o Java/entitlement/reserva/RDS PostgreSQL atualizados; **não usar Aurora em nenhum ambiente**.
- [x] Preparar testes HTTP: autorização, replay mesma chave, chave com outro conteúdo, conta/tenant negado 403, limite insuficiente, dois débitos concorrentes, `CAPTURA/LIBERACAO` parciais, expiração e estorno.
- [ ] Demonstrar Postgres local: schema Flyway, Inbox, ledger e partidas dobradas; Kinesis/SQS/EventBridge simulados conforme suporte real do LocalStack escolhido.
- [ ] Adicionar cenário local **kind/k3d** opcional, com overlay diferente dos manifests exclusivos do EKS (`TargetGroupBinding`, Pod Identity), ou explicar objetivamente a limitação. LocalStack não comprova EKS gerenciado.
- [ ] Documentar os comandos na ordem exata, pré-requisitos no Mac, logs esperados, critérios de aceite e diagnóstico de falhas; **não dizer que passaram antes de execução**.
- **Aceite:** roteiro reproduzível no VS Code; o usuário fará a execução e compartilhará logs.

## Fase 6 — Well-Architected, FinOps e capacidade [P1]

- [ ] Registrar avaliação dos seis pilares **Operational Excellence, Security, Reliability, Performance Efficiency, Cost Optimization, Sustainability**, com riscos e evidências, cruzando cada ponto com o PDF do Itaú e a FSI Lens.
- [ ] Definir SLO/SLA, p95/p99, taxa de 5xx, throughput, backlog Kinesis, lag de conciliação, RPO/RTO e critérios de DR/backup/restore.
- [ ] Dimensionar pico de **5.000 TPS**, contenção por contrato/hot partition, DynamoDB RU/WU, Kinesis shards/consumidores, número de Pods/nós e writer RDS, com testes k6 planejados para ambiente adequado (não forçar 5.000 TPS no Mac).
- [ ] Refazer **TCO sa-east-1** para EKS Auto Mode + RDS PostgreSQL Multi-AZ, VPC Endpoints, ECR, observabilidade, Scheduler, IAM/KMS, DynamoDB entitlement, EventBridge opcional, S3 e recuperação; **não reutilizar os ~US$35 mil/mês da proposta ECS/Aurora**.
- [ ] Documentar hipóteses de 100/500/1500 TPS médios separadamente do pico solicitado, incluindo retenção contábil e proporção de eventos que exigem lançamento.
- **Aceite:** custos e desempenho apresentados como hipóteses até medição e orçamento verificáveis.

## Fase 7 — Documentação e defesa [P2]

- [ ] Corrigir **todos** os READMEs, roteiro, `docs/visao-da-solucao.md`, `docs/arquitetura.md`, `docs/custos.md` e diagramas que ainda apresentem ECS Fargate/Aurora como arquitetura vigente; manter histórico assinalado.
- [ ] Atualizar pôster e fluxo de arquitetura em **PNG/SVG com ícones AWS**: EKS privado/Pods/Pod Identity, RDS Postgres Multi-AZ, NLB/API Gateway, DynamoDB, Streams/Kinesis/EventBridge, SNS/SQS, conciliação.
- [ ] Consolidar ADR 042–044 com decisões anteriores e registrar escolhas finais de TLS/workload identity, storage/particionamento, expiração, quotas e DR.
- [ ] Atualizar `docs/auditoria/RELATORIO-FINAL.md` com **implementado / validado / pendente**, links para arquivos e commits, testes realmente executados e riscos residuais.
- [ ] Preparar defesa de 20 minutos + perguntas: **por que EKS e não ECS? por que RDS Postgres e não Aurora?** Mini-saga, BIAN/DDD, invariantes financeiras, throughput, IAM/segurança, DR e custo.
- **Aceite:** documentação sem contradição entre arquitetura nova, Terraform, imagens, contratos e roteiro.

## Protocolo de execução para o Codex

1. Trabalhar **somente na primeira fase com pendências não resolvidas**. Consultar GitHub atual antes de mudar algo.
2. Antes de implementar, escrever diagnóstico curto com arquivos e critérios de aceite.
3. Fazer alterações mínimas e testes de regressão sem trocar DDD/BIAN nem reescrever serviços sem evidência.
4. Commitar cada fase separadamente; atualizar **este checklist** e `apresentacao-itau/docs/STATUS-EKS-RDS.md` marcando `[x]` somente com evidência de conclusão.
5. Entregar resumo por fase: arquivos alterados, comandos efetivamente executados, testes pendentes no Mac, riscos em aberto e SHA do commit.
6. Não interromper o processo para pedir informações que já existem nos ADRs, no código ou no PDF. Se faltar um dado **de negócio** ou de **conta AWS**, documentar como hipótese/pendência, nunca inventar.

## Execução da Fase 0 — 22/10/2026

**Parcial; não iniciar a Fase 1 ainda.** Baseline local e GitHub `main` conferidos em
`87551fe9b71ceb611a44d57b77fec4e3f2832ed8`. Histórico recuperado com `git fetch --unshallow origin`;
diff desde `ad35be77` inspecionado (115 arquivos), marco preservado.

- Corrigidos blocos HCL inválidos em `dados`, `eks` e outputs de produção; formatação recursiva em 15 arquivos Terraform.
- Verificador EKS/RDS corrigido para aceitar alinhamento de `terraform fmt`.
- Passaram: os dois verificadores Python e `terraform fmt -check -recursive`.
- Pendentes: Maven ausente, Java 17 em vez de 21; init bloqueado pelo Terraform 1.5.7 (<1.9); validate sem provider instalado. Lockfile AWS 5.100.0 diverge do requisito >=6.15,<7.0.
- PDF não localizado; ADRs 026–031 lidos no catálogo, 036–044 em arquivos; 032–035 não possuem registros individuais e sua catalogação continua pendente. `docs/MEMORIA.md` consultado via Git porque o checkout é esparso.
- Inventário, arquivos, riscos, comandos para o Mac e evidências: [STATUS-EKS-RDS](apresentacao-itau/docs/STATUS-EKS-RDS.md#revisão-da-fase-0--22102026).
- Commit desta correção: registrado abaixo após criação do commit; sem push.

Commit das correções e evidências da Fase 0: `d72c1075702929e226115867196b3607b8f445ba` (local, sem push).

## Revisão de entrega com PDF — 22/10/2026

PDF original localizado em Downloads e lido integralmente (5 páginas). Parecer: **ainda não pronto para entrega final**. Demonstração Docker é aceita pelo §5; Aurora não é obrigatório. Revisão estática/documental, sem novos testes de runtime. Ver [relatório](apresentacao-itau/docs/REVISAO-PRONTIDAO-ENTREGA.md) para evidências, seis pilares, prioridades e critérios de liberação. Fase 0 permanece parcial.

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

## Subfase local — execução reproduzível (22/10/2026)

Correção de contrato consolidada no commit publicado `25f826f`. Preparação local priorizada por instrução do usuário.
`local/executar-tudo.sh` coordena build/setup/aceite e guarda log local; `demo.sh` e
`demo-ciclo-reserva.sh` usam verificador Python com falha explícita. Conferidos: status
HTTP, vínculo canal/contrato/conta, revogação, replay, concorrência, saldo individual,
expiração após prazo real, Inbox e partidas dobradas. Estes são **cenários implementados**,
não E2E aprovado. Rede Docker explícita e Terraform local restrito ao loopback.

Validações executadas: 142 testes Java sem falhas/erros/skip, três testes Python do
verificador, dois verificadores estáticos e fmt Terraform local. Evidências e limites:
`apresentacao-itau/docs/evidencias/localstack-readiness-2026-10-22.txt` e ADR-046.
Docker ausente: E2E/IT pendentes no Mac. Terraform validate desta revisão bloqueado por
checksum do provider em cache; lockfile original preservado. Sem Actions/apply AWS.

Arquivos alterados nesta subfase: `local/{executar-tudo.sh,subir.sh,demo.sh,demo-ciclo-reserva.sh,
verificar-demo.py,preflight.py,test_verificar_demo.py,docker-compose.yml,LEIAME.md}`,
`COMO-RODAR.md`, `.gitignore`, comentários do Dockerfile e ambiente Terraform local,
validação do endpoint local, README da raiz, MIGRACAO-EKS-RDS, STATUS e ADR-046.

**Abertos:** produção Fases 2–4/6–7; fonte de entitlement; TLS/identidade interna; TLS e
roles SQL; recuperação, capacidade e operação AWS. O aceite do Mac será a evidência
necessária para fechar os itens de demonstração da Fase 5.

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
