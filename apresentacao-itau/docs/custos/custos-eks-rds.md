# FinOps da solução escolhida: EKS Auto Mode e RDS PostgreSQL

**Status:** novo desenho, não precificado nem medido em AWS. A estimativa antiga
`docs/custos.md` refere-se a **ECS Fargate + Aurora** e está **OBSOLETA** para esta proposta.

## Componentes de preço obrigatórios

- **EKS:** taxa fixa de controle EKS por cluster/hora, compute de nodes (EC2 + camada Auto Mode conforme preço vigente), armazenamento de imagens ECR, CloudWatch/X-Ray/ADOT, tráfego NLB/cross-zone, VPC interface endpoints/hora e GB.
- **RDS PostgreSQL standard Multi-AZ:** instâncias primary/standby (cobrança MultiAZ), gp3 GB/mês e IOPS/throughput provisionados acima do baseline, backups, snapshots, logs, transferência e exportação S3. Não usar tarifa de Aurora I/O-Optimized.
- **Domínio:** DynamoDB leitura forte na tabela de entitlement **uma consulta adicional por autorização**, transações com novo item `RESERVA#`, Kinesis + Firehose, Step Functions, Scheduler, SNS/SQS, WAF/REST API Gateway.
- **S3:** trilha WORM, exports diários, Athena bytes escaneados e Glacier.

## Demandas de volume

| Cenário de TPS médio | Autorizações em 30 dias | Máximo hipotético de linhas contábeis (4 linhas/fato) |
|---:|---:|---:|
| 100 | 259,2 milhões | 1,037 bilhão |
| 500 | 1,296 bilhão | 5,184 bilhões |
| 1.500 | 3,888 bilhões | 15,552 bilhões |

**Importante:** o número de linhas é limite de cenário, não contabilidade real
(tipos recusados, reservas e liberações sem lançamento não geram as quatro linhas).
Precisa medir percentuais de fatos contábeis, tamanho de linhas/índices,
retenção operacional e capacidade de escrita (WAL, IOPS, CPU).

## Decisão

Não apresentar os ~US$ 35 mil/mês da versão Fargate/Aurora como custo EKS/RDS.
Preencher novo orçamento com AWS Pricing Calculator, região sa-east-1,
valores reais de carga/permanência de reserva e custo de recuperação.

Quando escolher EKS e RDS não-Aurora, ganhamos controle operacional e
facilidade de portabilidade PostgreSQL/Kubernetes, mas assumimos maior
responsabilidade de dimensionamento, observabilidade e sustentação.
