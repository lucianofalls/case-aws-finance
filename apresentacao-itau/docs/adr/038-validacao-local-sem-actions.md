# ADR-038 — Escopo de teste local sem GitHub Actions

**Data:** 2026-10-21  
**Status:** Adotada como estratégia de validação, testes E2E pendentes  
**Pilares:** Operational Excellence, Reliability, Security, Cost Optimization

## Contexto
O ZIP foi gerado para ser executado via Docker Compose com LocalStack 4.0, PostgreSQL e Jaeger. O usuário deseja testar localmente e determinou expressamente **não utilizar GitHub Actions**.

## Decisão
- Nenhum arquivo `.github/workflows` será criado; testes locais são iniciados manualmente.
- Preservar `local/subir.sh`, `local/demo.sh` e `local/derrubar.sh` executáveis, além de `contracts/verificar-contrato-api.sh` e `tests/k6/autorizacao.js`.
- Manter `infra/terraform/ambientes/local` com endpoint `http://localhost:4566` e credenciais fictícias, **jamais aplicar o ambiente prod automaticamente**.
- Restringir **todas as portas do Compose ao loopback `127.0.0.1`** para evitar exposição de APIs, banco de dados e LocalStack a outros equipamentos da rede.
- `local/verificar-estatico.py` roda offline e confere formatos/estrutura; **não substitui** compilação Maven, JUnit, Terraform validate ou testes E2E.
- Na máquina de desenvolvimento, executar `mvn verify` / `mvn verify -Pintegracao` e testar `bash local/subir.sh` seguido de `bash local/demo.sh` somente após validar os endpoints locais.

## Limitações da simulação
- Cognito, WAF, API Gateway, IAM/VPC, Firehose Object Lock e Aurora de produção não são plenamente reproduzidos no Compose.
- DynamoDB Streams, Kinesis e Lambda na versão do LocalStack precisam ser demonstrados em execução; fixar imagem 4.0 **não é prova de compatibilidade** com Docker/Java atuais.
- As estimativas de custo e capacidade (5.000 TPS) não são comprovadas por emulação de serviços AWS.

## Critérios de aceite local
1. Build e testes Java (incluindo concorrência financeira) aprovados.
2. Compose saudável do zero, banco e eventos inicializados.
3. HTTP 201/200/402/409/422/503 e correlationId demonstrados.
4. Evento da autorização chega à contabilidade com Inbox e partidas dobradas.
5. Injeção de falha entre escrita no Limites e decisão na Autorização: órfã detectada e revertida.
6. Quarentena e retry simulados; cenário de Streams >24 h descrito/testado por reconciliador.
7. `docker compose down` sem efeitos fora do host local.
