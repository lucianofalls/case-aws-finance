# ADR-042 — EKS Auto Mode + RDS PostgreSQL Multi-AZ (substitui ECS Fargate e Aurora)

**Status:** Implementação Terraform proposta; `terraform validate/plan` e deploy em AWS pendentes. 22/10/2026.

## Decisão obrigatória do case

A execução de produção passa a usar **EKS Auto Mode** (Pod Identity, três bounded contexts em Pods) e **Amazon RDS for PostgreSQL Multi-AZ** (instância writer + standby síncrono, **não Aurora**). O código Java mantém Port/Adapter, DynamoDB para Autorização/Limites, Kinesis/eventos e Postgres para Inbox/partidas dobradas.

- Os manifests Kubernetes ficam em `k8s/` e são renderizados **offline**; nenhum `kubectl apply` ocorre automaticamente.
- O NLB **interno** é criado no Terraform; o controller gerenciado do EKS Auto Mode associa os IPs dos Pods às target groups com `TargetGroupBinding eks.amazonaws.com/v1` e tag `eks:eks-cluster-name`.
- API Gateway REST/Cognito/WAF permanecem na borda via VPC Link para NLB; a Lambda reconciliadora usa porta 8081 do mesmo NLB.
- Cluster API endpoint apenas privado; uso de rede corporativa ou runner na VPC para `kubectl`.
- Pod Identity distinto por ServiceAccount de Autorização, Limites e Contabilização; nenhum AWS static key dentro do Pod.
- RDS PostgreSQL `db.r7g.2xlarge`, `gp3`, Multi-AZ, KMS, backup de 30 dias, PITR e Secrets Manager. Tamanho/IOPS são premissas a testar para picos 5.000 TPS.
- **RDS Data API removida da conciliação.** Foi adicionada Lambda **JDBC em VPC** que chama `aws_s3.query_export_to_s3` (extensão também suportada por RDS PostgreSQL); a role de exportação S3 passa a se associar a `aws_db_instance`.

## Trade-offs

EKS oferece integração Kubernetes, Pod Identity, manifests padronizados, políticas e HPA, mas acrescenta custo/control-plane e exigência de operação Kubernetes; Auto Mode reduz responsabilidade sobre nós, load balancing e storage. Comparado ao Fargate anterior, exige observar limite de Pods/endereços IP, capacidade da região e resiliência de AZ.

RDS PostgreSQL comum atende a escolha da equipe e mantém SQL/PostgreSQL, mas a configuração Multi-AZ de instância não inclui reader independente nem Data API. A reconciliação precisa passar pela camada JDBC e contas contábeis continuam como exemplo (o plano COSIF definitivo depende do negócio). Para PostgreSQL multi-AZ writer/standby, failover deve ser testado; a aplicação deve reconectar e usar pooling com limites de conexões.

## Segurança: pendências explícitas

- NetworkPolicies protegem entrada de Limites/Contabilização, mas autenticação forte de workload/mTLS leste-oeste permanece **pendente**; não declarar conformidade bancária sem isso.
- Pod Identity limita ações AWS por conta de serviço. **Revalidar policies** com IAM Policy Autopilot baseado no código Java:
  `uvx iam-policy-autopilot@latest generate-policies <arquivos-java-com-aws-sdk> --tf-dir <caminho-absoluto>/infra/terraform/ambientes/prod --region sa-east-1 --service-hints dynamodb kinesis sqs --pretty`.
- Certificados TLS do RDS devem ser validados (`verify-full` e bundle CA antes de prod). Enquanto isso, `sslmode=require` garante criptografia, mas não valida o servidor. O Kubernetes Secret manual é somente provisório; usar External Secrets Operator/Secrets Manager com rotação.
- Verificar efetividade das NetworkPolicies no CNI/Auto Mode. Bloquear criação de TargetGroupBinding por workloads não autorizados via RBAC.

## Referências AWS

- [Cluster EKS Auto Mode](https://docs.aws.amazon.com/eks/latest/userguide/create-cluster-auto.html)
- [Pod Identity](https://docs.aws.amazon.com/eks/latest/userguide/pod-id-association.html)
- [Auto Mode TargetGroupBinding](https://docs.aws.amazon.com/eks/latest/userguide/auto-configure-nlb.html)
- [RDS PostgreSQL Multi-AZ](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZSingleStandby.html)
- [RDS PostgreSQL exportação S3](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/postgresql-s3-export.html)
