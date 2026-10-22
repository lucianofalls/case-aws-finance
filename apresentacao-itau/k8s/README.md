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
