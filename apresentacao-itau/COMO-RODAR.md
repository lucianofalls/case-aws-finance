# Executar o case Itaú localmente

O ambiente usa LocalStack para DynamoDB, Streams, Kinesis, EventBridge, SQS e Lambdas;
PostgreSQL 16 para Inbox/ledger; três serviços Java 21 em Docker; Jaeger para traces.
Produção permanece **EKS Auto Mode + RDS PostgreSQL Multi-AZ convencional**.

## Pré-requisitos no Mac

- Docker Desktop ativo, Compose v2 com suporte a `up --wait` (2.20+).
- JDK 21, Maven 3.9+, Terraform 1.9+, Python 3.9+, OpenSSL, curl, Bash e Git.
- Ponto inicial de recursos: 6–8 GB de RAM no Docker e 15 GB livres. Ajuste conforme
  consumo dos containers Java e dos runtimes Lambda; isso não é dimensionamento de produção.
- Internet na primeira execução para Maven, providers Terraform, imagens e agente ADOT.
- Portas locais livres: 4566, 5432, 8080, 8082, 8083, 16686 e 4317.

```bash
java -version
mvn -v
terraform version
docker compose version
docker info
python3 --version
openssl version
```

Se faltarem ferramentas no macOS, instale-as pelos respectivos canais oficiais. Exemplo
com Homebrew: `brew install maven python openssl` e `brew install --cask temurin@21`.
Configure `JAVA_HOME` para o JDK 21. Terraform deve ser obtido pelo canal HashiCorp.

## Execução completa

```bash
git clone https://github.com/lucianofalls/case-aws-finance.git
cd case-aws-finance/apresentacao-itau
bash local/executar-tudo.sh
```

Se o repositório já estiver na máquina, atualize o ramo que contém estas correções antes
de executar. O script grava evidências em `local/evidencias/aceite-*.log` (fora do Git),
interrompe em qualquer erro e preserva os containers para diagnóstico.
Para rodar os testes DynamoDB Local/Testcontainers além do E2E:

```bash
INTEGRACAO=1 bash local/executar-tudo.sh
```

## Execução por etapas

```bash
bash local/subir.sh
bash local/demo.sh
# Opcional: somente um ciclo de reserva em contrato novo
bash local/demo-ciclo-reserva.sh
```

`subir.sh` confere pré-requisitos, gera uma chave RSA local, executa build e testes,
copia os artefatos Lambda, sobe LocalStack/Postgres/Jaeger, valida e aplica **somente**
o Terraform de `infra/terraform/ambientes/local`, espera tabelas/Streams, Kinesis, filas, sete Lambdas e seus event-source mappings,
confere o destino EventBridge, constrói os serviços e espera sua saúde.
Em falha, a entrada única coleta `compose ps` e os últimos logs automaticamente.
O endpoint Terraform local aceita somente loopback:4566 e usa credenciais fictícias `test`.
Variáveis `TF_CLI_ARGS*`, `TF_WORKSPACE` e `TF_DATA_DIR` são rejeitadas para evitar
redirecionamento silencioso. Não execute apply em `ambientes/prod` para esta demo.

## O que o aceite verifica

| Cenário | Resultado exigido |
|---|---|
| Registro legado → ACL → EventBridge → SQS → Limites | Contrato novo com limite 1.000,00 |
| Header forjado sem JWT / token inválido | 401, sem movimento |
| Outro canal autenticado ou conta divergente | 403, sem movimento |
| DEBITO | 201 |
| Mesmo payload/chave | 200, mesmo resultado e sem novo débito |
| Mesma chave/outro payload | 422 |
| Valor acima do limite | 402 |
| Campo desconhecido, fração de centavo, estorno sem original | 422 |
| CREDITO e ESTORNO parcial | 201, posição conferida |
| Revogação administrativa local | 403 inclusive no replay da decisão anterior |
| Dois débitos concorrentes de 600,00 / limite 1.000,00 | Um aprovado, outro recusado, saldo 400,00 |
| Limites parado | 503 + Retry-After; serviço sempre reiniciado pelo finally |
| RESERVA 100 → CAPTURA 30 → LIBERACAO 20 | Disponível 920, bloqueado 50, utilizado 30 |
| Outra reserva de 40 e captura de 60 contra a primeira | 422 apesar de haver 90 bloqueados no total |
| Liberação da segunda e expiração da primeira | Disponível 970, bloqueado zero, utilizado 30 |
| Reentrega SQS da expiração | Sem outra autorização de liberação ou mudança de saldo |
| Streams → relay Lambda → Kinesis → KCL → PostgreSQL | Inbox, registros únicos e partidas dobradas balanceadas |

Reservas e liberações entram no Inbox, mas **não geram lançamento contábil**; a captura
gera. O verificador trata essa diferença e também confere IDs, não apenas existência de linhas.
Qualquer divergência termina o processo com exit 1. A saída final esperada é
`ACEITE LOCAL APROVADO` com os limites do teste. Isso só é evidência após executar.

## Identidade e expiração locais

Os tokens são emitidos por `local/token-demo.py`, aceitos apenas pelo perfil `local`
e valem 15 minutos. A chave privada fica em `local/.segredos-demo/`, ignorada pelo Git.
O backend verifica RS256, emissor, validade, `token_use`, `client_id` permitido e escopo.
`X-Client-Id` não autentica nem substitui o token. A projeção de permissões é preenchida
diretamente pelo roteiro com dados sintéticos; a fonte corporativa permanece pendente.

A validade da reserva local é **PT2M**. O verificador lê `reserva_expira_em` do DynamoDB,
espera o prazo e só então simula a entrega do Scheduler pela SQS. Não altera relógio,
não apaga prazo do banco nem ignora a validação. Uma rodada pode levar vários minutos,
principalmente no primeiro download/início das Lambdas e do KCL.

Pode-se configurar `VALIDADE_RESERVA_LOCAL=PT3M bash local/executar-tudo.sh` em máquinas
mais lentas. O preflight aceita de 30 segundos a quatro minutos; produção mantém P7D.
Se mudar a validade, recrie o serviço pelo `subir.sh` antes da demonstração.

## Diagnóstico

```bash
docker compose -f local/docker-compose.yml ps
docker compose -f local/docker-compose.yml logs --tail 100 autorizacao limites contabilizacao
docker compose -f local/docker-compose.yml logs --tail 100 localstack
docker compose -f local/docker-compose.yml exec -T localstack awslocal lambda list-functions
docker compose -f local/docker-compose.yml exec -T localstack awslocal lambda list-event-source-mappings
docker compose -f local/docker-compose.yml exec -T postgres psql -U contabilizacao -d contabilizacao -c 'SELECT count(*) FROM evento_processado;'
```

| Sintoma | Ação |
|---|---|
| JDK 17 ou 25 no PATH | Selecione o JDK 21 em `JAVA_HOME`/PATH |
| Docker não responde | Abra Docker Desktop e aguarde o Engine |
| Porta ocupada | Pare o processo conflitante; mantenha endpoints do Compose e Terraform alinhados |
| Contrato não aparece | Confira ACL, EventBridge, fila `limites-concedidos` e event-source mappings |
| Reserva expirou antes da captura | Aumente validade local para PT3M/PT4M e repita com contrato novo |
| 401 | Recrie a chave pública/serviço se houve troca de chaves; confira perfil local e validade do JWT |
| Inbox vazio | Confira relay-autorizacoes, stream transacoes, KCL e quarentena |
| Lambda não encontra serviços | Rede explícita `financas_default`; Docker socket precisa estar montado |
| Checksum do provider Terraform | Apague apenas `infra/terraform/ambientes/local/.terraform` e execute novamente; preserve `.terraform.lock.hcl`. Se usar `TF_PLUGIN_CACHE_DIR`, confira/remova o pacote defeituoso desse cache antes de repetir. O setup usa lockfile readonly, sem inventar hashes |
| Lambda Failed / Docker not available | Confira acesso de containers ao Docker socket nas configurações do Docker Desktop; a montagem do socket é obrigatória para as Lambdas |
| Mapping não habilitado | Inspecione `list-event-source-mappings` e logs do LocalStack; o setup falha antes de enviar transações |
| Primeiro build demora | Downloads iniciais; acompanhe o log em vez de interromper |

## Encerramento

```bash
bash local/derrubar.sh
```

Remove containers, volumes locais e estado Terraform local. Tokens/chaves locais e logs
permanecem para inspeção e não devem ser enviados ao Git. Para inspecionar traces, use
http://localhost:16686 antes de desligar.

## Limites da demonstração

A imagem LocalStack continua fixada no baseline legado `4.0`. A compatibilidade desta
versão deve ser confirmada no aceite do Mac; versões atuais podem mudar serviços e
condições de licença. Não usamos o emulador como prova de IAM, EKS, Pod Identity,
NLB/VPC Link, WAF/429, Cognito real, Scheduler real, TLS/failover/PITR RDS, Athena/Glue,
conciliação diária ou 5.000 TPS. O PostgreSQL local é convencional, sem Aurora/Data API.
Segurança leste-oeste, papéis SQL/rotação/TLS e capacidade de produção continuam no
[PENDENCIAS-CODEX.md](../PENDENCIAS-CODEX.md). Não há GitHub Actions ou deploy AWS automático.

Referências: [LocalStack Lambda](https://docs.localstack.cloud/aws/services/lambda/),
[rede LocalStack](https://docs.localstack.cloud/aws/customization/networking/),
[Spring Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
