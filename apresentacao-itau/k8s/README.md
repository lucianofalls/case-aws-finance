# Deploy dos microsserviços no EKS Auto Mode (etapa AWS opcional)

**Este diretório contém infraestrutura declarativa pronta para revisão, não implantação executada.**
Docker Compose local continua usando PostgreSQL em container e LocalStack.

## Dependências e sequência

1. `terraform plan` e `terraform apply` de **prod** (somente após autorização), criando EKS/RDS/DynamoDB/Kinesis/NLB/API. O cluster é **privado**; execute `kubectl` via VPN/SSM/runner na VPC. Autenticação do cluster via EKS Access Entries, não acesso público aberto.
2. Construa/publicar as imagens ARM64 Java21 no ECR pelo processo manual (sem GitHub Actions). Informe as três tags imutáveis no `IMAGE_*`.
3. Configure as variáveis do `render.py`, extraídas dos outputs Terraform (incluindo Target Group ARN de `eks_target_groups`), `RDS_HOST` e URLs SQS. Nenhuma senha vai ao repositório.
4. Crie Kubernetes Secret `rds-contabilizacao` com chave `password` via integração corporativa Secrets Manager/External Secrets Operator **ou** provisione temporariamente por processo seguro antes de aplicar; não use plaintext em YAML/commit.
5. `python3 k8s/render.py` (seguro; offline) produz `rendered-stack.yaml`; revise e então `kubectl apply -f` (**somente com autorização de deploy AWS**).
6. EKS Auto Mode usa `TargetGroupBinding` API `eks.amazonaws.com/v1` para ligar IPs dos pods aos target groups do **NLB interno já criado no Terraform**. Requer suporte EKS Auto Mode da região e RBAC restrito para TGB.
7. Confirme `kubectl rollout status -n financas deploy/autorizacao`, `limites`, `contabilizacao`, TargetGroupBinding reconciliation e targets saudáveis no NLB. Sem pods saudáveis a API 503.

## Segurança operacional

- São três ServiceAccounts com Pod Identity IAM separados por bounded context.
- O Secret deve ser sincronizado/rotacionado (External Secrets Operator recomendado); `kubectl create secret` manual **não é operação de produção**.
- Rede east-west tem NetworkPolicy e SG, mas **mTLS/autenticação workload-to-workload ainda não está implementada**. Não declarar prontidão bancária até este controle.
- Node placement e limites HPA são hipóteses; validar recursos/zonas e SLO de 5.000 TPS.
- Não há repositório GitHub Actions e não se faz AWS deploy automaticamente.

## Exemplo seguro de render sem AWS

```
# export REGION=sa-east-1 IMAGE_AUTORIZACAO=... IMAGE_LIMITES=... IMAGE_CONTABILIZACAO=...
# export demais parâmetros conforme README e Terraform outputs
python3 k8s/render.py
```

## Terraform e segurança adicional

A stack ativa usa `infra/terraform/ambientes/prod`; o módulo `compute/` antigo foi arquivado.
O `k8s/render.py` **não valida** TargetGroupBinding em AWS: a aplicação dos manifests exige
permissão de operador e conectividade privada com o cluster.
A API Gateway mantém a mesma URL contratual; o NLB interno encaminha 80→Autorização e 8081→Limites.
A reconciliação executa via Lambda JDBC, pois o RDS PostgreSQL **não oferece Aurora Data API**.


## Privilégios PostgreSQL e Flyway (produção)

**Nunca execute os Pods de Contabilização com o usuário administrador do RDS.**
O manifesto usa `DB_USUARIO=contabilizacao_app` e `SPRING_FLYWAY_ENABLED=false`.
Antes do deploy:

1. Como operador DBA autorizado, execute as migrações Flyway **uma vez**, com uma identidade DDL de curta duração e logs de mudança. O serviço em execução não deve ter `CREATE/ALTER/DROP`.
2. Provisione a role `contabilizacao_app` usando o runbook [RDS usuários e segredos](../docs/runbooks/rds-postgres-acessos.md). Crie seu segredo próprio no Secrets Manager e sincronize a chave `password` para o Secret Kubernetes `rds-contabilizacao` por External Secrets Operator ou integração corporativa equivalente.
3. **Não reutilize** `master_user_secret` (RDS admin) para `DB_SENHA` da aplicação.
4. A Lambda de exportação do razão usa o **ARN obrigatório da role de exportação**, configurado em `TF_VAR_segredo_exportacao_rds_arn`. Provisionar a role e o segredo antes do `terraform plan`; a credencial master do RDS não é mais utilizada pelo exportador.
5. Validar TLS PostgreSQL com `sslmode=verify-full` e CA RDS corporativamente confiável, não apenas `sslmode=require`.
6. Testar rotação do Secret/rolling restart, failover Multi-AZ, total de conexões e backpressure do Kinesis.

O guia é preparação declarativa, não prova de execução no cluster.

### JWT de entrada (ADR-045)

O renderer exige `JWT_ISSUER`, `JWT_JWKS_URI` e `JWT_CLIENTES_PERMITIDOS` (client IDs separados por vírgula).
Usar issuer do pool Cognito, JWKS HTTPS confiável e somente aplicações autorizadas.
Garantir rota de saída controlada para JWKS ou espelho corporativo autenticado no cluster privado.
Sem essas configurações, o serviço falha fechado; X-Client-Id não autentica.
No Compose, executar `python3 local/token-demo.py --preparar` antes de subir; os scripts de demo usam JWT local.
