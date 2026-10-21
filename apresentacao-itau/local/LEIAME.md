# Ambiente local

O passo a passo completo (pré-requisitos por sistema, resultado esperado e problemas comuns) está em
[../COMO-RODAR.md](../COMO-RODAR.md).

```bash
bash local/subir.sh     # pré-requisitos → build + testes → LocalStack/Postgres/Jaeger → terraform apply (local) → serviços
bash local/demo.sh      # roteiro dos passos 0–9 (201, 200, 422, 402, concorrência, 503, eventos, traces)
bash local/derrubar.sh  # remove containers, dados e o estado local do Terraform
```

| Componente | Local | AWS |
|---|---|---|
| autorizacao / limites / contabilizacao | containers (mesma imagem) | ECS Fargate ARM64 |
| DynamoDB, Streams, Kinesis, SQS, EventBridge, Lambdas | LocalStack 4.0 (mesmos módulos Terraform) | serviços gerenciados |
| Aurora PostgreSQL | `postgres:16` | Aurora (Graviton, Multi-AZ) |
| API Gateway, Cognito, WAF, usage plans (429) | fora (chamada direta com `X-Client-Id`) | `ambientes/prod` |
| Glue Schema Registry, Firehose/S3 Object Lock | desligados por flag (`modulos/eventos`) | `ambientes/prod` |
| X-Ray | Jaeger (mesmo agente OpenTelemetry) | ADOT → X-Ray |

Diferenças de configuração em relação à AWS, todas no `docker-compose.yml`:

| Configuração | Por quê |
|---|---|
| `localstack/localstack:4.0` fixado | última linha Community sem cadastro; as versões de 2026 exigem auth token |
| `LAMBDA_IGNORE_ARCHITECTURE=1` | as Lambdas são `arm64` (Graviton) na AWS; localmente rodam na arquitetura da máquina |
| `LOCALSTACK_HOST=localstack:4566` | as URLs de fila devolvidas pelo LocalStack precisam ser resolvíveis de dentro dos containers |
| `KCL_MODO_LEITURA=polling` | o enhanced fan-out do KCL usa HTTP/2 (`SubscribeToShard`), confiável só na AWS |
| `AWS_CBOR_ENABLED=false` | Kinesis em JSON no emulador (SDK v2: `aws.cborEnabled`) |

Os testes de concorrência também rodam sem Docker, com repositórios em memória de mesma semântica. O teste do
adaptador DynamoDB real roda com `mvn verify -Pintegracao` (Testcontainers; exige Docker).
