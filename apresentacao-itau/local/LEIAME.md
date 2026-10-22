# Executar localmente

Pré-requisitos e diagnóstico: [COMO-RODAR.md](../COMO-RODAR.md).

```bash
bash local/executar-tudo.sh
# ou em etapas:
bash local/subir.sh
bash local/demo.sh
# ao terminar (remove dados locais):
bash local/derrubar.sh
```

| Componente | Execução local | Arquitetura de produção |
|---|---|---|
| Autorização, Limites, Contabilização | Containers Docker Compose | Pods no EKS Auto Mode |
| DynamoDB/Streams/Kinesis/SQS/EventBridge/Lambda | LocalStack; módulos Terraform compartilhados | Serviços AWS |
| Ledger e Inbox PostgreSQL | PostgreSQL 16 + Flyway no container | RDS PostgreSQL convencional Multi-AZ |
| Identidade da entrada | JWT RS256 local, assinatura/issuer/client_id/scope validados | IdP/Cognito + Resource Server |
| Expiração | Validade local de 2 minutos; mensagem SQS após o vencimento | Política de 7 dias + EventBridge Scheduler |
| Observabilidade | Jaeger | Collector/ADOT e monitoramento corporativo |

LocalStack usa imagem legada `4.0` para manter o baseline do projeto; não representa
suporte validado nas versões atuais. Não é necessário inserir credenciais AWS.
O teste não emula EKS, Pod Identity, NLB, WAF, Cognito real, TLS/failover RDS,
Scheduler, conciliação Athena/Glue/S3 diária ou performance de 5.000 TPS. Esses aceites
continuam separados no backlog. Autorização/ledger/eventos são exercitados de ponta a
ponta nos componentes locais, sem prometer equivalência operacional com produção.
