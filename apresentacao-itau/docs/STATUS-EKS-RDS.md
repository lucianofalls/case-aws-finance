# Checklist de implementação EKS/RDS PostgreSQL — 22/10/2026

**Ramo `main`, sem GitHub Actions; sem aplicar AWS.**

| Pri. | Requisito | Status no Git | Aceite obrigatório |
|---|---|---|---|
| P0 | Operações financeiras com reserva por ID | Código e testes criados | Maven/Testcontainers: CAPTURA, LIBERACAO e expiração concorrentes |
| P0 | Contrato OpenAPI/DTO seis operações | Código atualizado | HTTP 201/422 e contrato sincronizados |
| P0 | Conta pertence ao contrato | Código | Teste E2E conta divergente |
| P0 | Cliente autenticado autorizado em contrato | DynamoDB entitlement fail-closed | Provisionamento corporativo, revogação e teste cruzado 403 |
| P0 | EKS substitui ECS em prod | Módulo EKS + manifests | Terraform plan EKS Auto Mode sa-east-1, NLB TG binding |
| P0 | RDS PostgreSQL substitui Aurora | `aws_db_instance` Multi-AZ | terraform plan, conexão TLS/JDBC, restauração backup |
| P0 | Conciliação sem Data API Aurora | Lambda JDBC + Step Functions | Exercitar export AWS RDS `aws_s3` end-to-end |
| P0 | Segurança de banco: sem app admin | **Pendente** | Role DML e job migração separadas; Secrets Manager/External Secrets |
| P0 | K8s Secrets e IAM | Pod Identity codificada | Pod consegue acessar Dynamo/Kinesis/SQS sem chaves estáticas |
| P0 | JWT e zero trust interno | **Pendente** | validação token downstream, policy namespace/egress/TLS |
| P1 | Cluster privado e PrivateLink | Endpoints ec2/eks-auth/ecr/s3/logs/... | Testar bootstrap de nodes sem NAT |
| P1 | Autoscaling | HPA + addon metrics-server + EKS Auto Mode | Teste escala 5.000 TPS, backpressure Kinesis e p99 |
| P1 | Durabilidade RDS | Multi-AZ+backups+proteção exclusão | PITR/restore e RPO/RTO medidos |
| P1 | Capacidade/retention contábil bilhões/mês | **Não dimensionada** | Benchmark writer, particionamento e arquivamento |
| P1 | Expiração em alta escala | Scheduler por reserva | Dimensionar quota reservas ativas e custo |
| P1 | Observabilidade EKS | logs control plane, Java telemetry | Deploy ADOT e alarmes workload/HTTP/DB |
| P1 | Outbox/replay após TTL | Manual histórico | Ensaiar retentativas/DLQ e recuperação durável |
| P1 | EventBridge transações | Pipes opcional OFF | Avaliar quotas/custos e execução |
| P2 | Diagramas AWS e custos EKS/RDS | Docs Markdown atualizados | PNG AWS official icons, PRICING CALCULATOR na região |
| P2 | RTO/RPO e DR | Documentado | Exercício regional e runbook validado |

## Testes no Mac (SOMENTE após receber confirmação de conclusão do ciclo de correções)

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
