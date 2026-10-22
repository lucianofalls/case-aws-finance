# Migração em fases — EKS e RDS PostgreSQL

Infraestrutura alvo: **Amazon EKS Auto Mode + Amazon RDS PostgreSQL Multi-AZ**, sem Aurora e sem ECS em produção. Consulte [a visão](apresentacao-itau/docs/visao-eks-rds.md), [o checklist](apresentacao-itau/docs/STATUS-EKS-RDS.md) e [a ADR da migração](apresentacao-itau/docs/adr/042-eks-automode-rds-postgres.md).

**Estado atual:** build Java e verificações estáticas aprovados na revisão LocalStack; execução Docker/LocalStack e AWS EKS/RDS pendentes. Consulte o STATUS para as evidências históricas de Terraform e a limitação do cache do provider nesta revisão. Não executar `terraform apply` em produção nem ativar GitHub Actions. O código local é demonstrativo; produção exige provisionamento de identidade/segredos e dimensionamento de PostgreSQL.