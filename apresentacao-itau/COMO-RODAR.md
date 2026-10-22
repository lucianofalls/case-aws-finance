# Como rodar a solução numa máquina nova

Guia para subir a plataforma inteira **localmente**, sem conta AWS e sem pagar nada. Os serviços AWS são simulados
pelo **LocalStack** (imagem padrão legada 4.0 para compatibilidade local) e provisionados
com o **ambiente Terraform local**, separado de produção. Imagens atuais podem exigir token
LocalStack e ter disponibilidade de serviços diferente; consulte as condições da sua licença.

---

## 1. O que vai rodar

| Sobe localmente | Onde |
|---|---|
| `autorizacao-service`, `limites-service`, `contabilizacao-service` (mesma imagem do Fargate) | containers |
| DynamoDB + Streams, Kinesis, EventBridge (regras e archive), SQS, Lambdas (relay, ACL, reconciliador, stubs) | LocalStack, criados pelo Terraform |
| PostgreSQL (no lugar do Aurora) | container `postgres:16` |
| Traces ponta a ponta (no lugar do X-Ray) | Jaeger, em http://localhost:16686 |

| Não sobe localmente | Por quê | Onde está |
|---|---|---|
| API Gateway, Cognito, WAF, usage plans (429) | Cognito e WAF não existem no LocalStack gratuito | `infra/terraform/ambientes/prod` |
| Glue Schema Registry | só no plano pago mais alto do LocalStack | idem; localmente, a validação dos eventos é feita no build (`ContratoDeEventosTest`) |
| Firehose → S3 Object Lock, Aurora | simplificação local | idem |

Localmente, a chamada vai direto ao `autorizacao-service`, com o header `X-Client-Id`. Na AWS, quem injeta esse
header é o API Gateway, a partir do token.

---

## 2. Pré-requisitos

| Ferramenta | Versão | Windows | macOS | Linux |
|---|---|---|---|---|
| **Docker** com Compose v2 | Docker Desktop 4.x ou Engine 24+ | Docker Desktop (backend WSL2) | Docker Desktop | Docker Engine + plugin `docker compose` |
| **JDK** | **21** | Eclipse Temurin 21 | `brew install --cask temurin@21` | Temurin 21 (pacote da distro ou SDKMAN) |
| **Maven** | 3.9+ | zip de maven.apache.org, com `bin` no PATH | `brew install maven` | pacote da distro ou SDKMAN |
| **Terraform** | 1.9+ | zip de developer.hashicorp.com, no PATH | `brew tap hashicorp/tap && brew install hashicorp/tap/terraform` | repositório da HashiCorp |
| **Bash** | — | **Git Bash** (vem com o Git for Windows) | nativo | nativo |
| curl | — | vem com o Git Bash | nativo | nativo |

Recursos: **6 GB de memória para o Docker** (no Docker Desktop, em Settings → Resources) e **15 GB livres em
disco**.

Portas que precisam estar livres: `4566` (LocalStack), `5432` (Postgres), `8080` (Autorização), `8082` (Limites),
`8083` (Contabilização), `16686` e `4317` (Jaeger).

Para conferir:

```bash
docker info >/dev/null && echo "docker ok"
java -version        # deve mostrar "21"
mvn -v
terraform -version
```

---

## 3. Passo a passo

> No Windows, rode os comandos no **Git Bash**, não no PowerShell nem no CMD.
> No macOS e no Linux, use `bash local/...`: o ZIP não preserva a permissão de execução dos scripts.

### 3.1 Descompactar e entrar na pasta

```bash
git clone https://github.com/lucianofalls/case-aws-finance.git
cd case-aws-finance/apresentacao-itau
```

**No GitHub os scripts foram normalizados para extensões `.sh` e `.js`.** A renomeação é necessária apenas para executar o ZIP original recebido:

```bash
find . \( -name "*.sh.txt" -o -name "*.js.txt" \) | while read -r f; do mv "$f" "${f%.txt}"; done
```

### 3.2 Subir tudo

```bash
bash local/subir.sh
```

O script faz, em ordem:

| Etapa | O que acontece | Se falhar |
|---|---|---|
| 0/5 | Confere Docker (rodando), Java 21, Maven, Terraform e curl | mostra exatamente o que falta |
| 1/5 | `mvn install`: compila os módulos e executa testes disponíveis | se falhar, confira saída Maven e relatório Surefire; não presumir contagem fixa |
| 2/5 | Copia os JARs das Lambdas para `artefatos/` | — |
| 3/5 | Sobe LocalStack, Postgres e Jaeger e espera ficarem saudáveis | porta ocupada ou Docker sem memória |
| 4/5 | `terraform apply` em `infra/terraform/ambientes/local`, **só contra o LocalStack** | veja a seção 5 |
| 5/5 | Constrói e sobe os 3 serviços e espera o `/actuator/health` de cada um | mostra os últimos logs do serviço que não subiu |

Termina com:

```
pronto: API http://localhost:8080 · Limites http://localhost:8082 · Jaeger http://localhost:16686
```

### 3.3 Rodar a demonstração

```bash
bash local/demo.sh
```

| Passo | O que demonstra | Resultado esperado |
|---|---|---|
| 0 | Legado → ACL → EventBridge → SQS → Limites | posição com limite de R$ 1.000,00 (a 1ª execução de uma Lambda Java pode levar ~1 min) |
| 1 | Autorização aprovada | `HTTP 201` com `saldo_reservado` |
| 2 | Repetição com a mesma `Idempotency-Key` | `HTTP 200`, o mesmo resultado, sem novo débito |
| 3 | Mesma chave, outro conteúdo | `HTTP 422` |
| 4 | Valor acima do disponível | `HTTP 402` em Problem Details |
| 5 | 50 débitos simultâneos de R$ 20 com R$ 900 disponíveis | no máximo 45 respostas `201`; o disponível nunca fica negativo |
| 6 | Payload inválido | `HTTP 422` |
| 7 | Limites fora do ar | `HTTP 503` com `Retry-After` (circuit breaker) |
| 8 | Evento no Kinesis → Contabilização | linhas `D`/`C` (partidas dobradas) no Postgres |
| 9 | Ciclo de reserva | RESERVA→CAPTURA parcial→LIBERACAO parcial→expiração simulada por SQS; verificar saldo bloqueado zero |
| 10 | Rastreio | abra http://localhost:16686 e procure por `autorizacao-service` |

### 3.4 Desligar

```bash
bash local/derrubar.sh
```

Isso remove os containers, os dados e o estado local do Terraform. Na próxima vez, use `local/subir.sh` de novo.

---

## 4. Verificações extras (opcionais)

| Comando | O que prova |
|---|---|
| `mvn verify -Pintegracao` | o adaptador DynamoDB **real** (imagem oficial `amazon/dynamodb-local`, via Testcontainers): expressões condicionais, transações e 50 débitos concorrentes. Exige Docker |
| `cd infra/terraform/modulos/openapi-api-gateway && terraform init -backend=false && terraform test` | a tradução do contrato OpenAPI para a definição do API Gateway (4 cenários) |
| `terraform -chdir=infra/terraform/ambientes/prod init -backend=false && terraform -chdir=infra/terraform/ambientes/prod validate` | o Terraform de produção é válido (não cria nada) |
| `bash contracts/verificar-contrato-api.sh <base.yaml> <novo.yaml>` | breaking change no contrato da API (usa `oasdiff`, pelo binário ou pelo Docker) |
| `k6 run -e API=... tests/k6/autorizacao.js` | teste de carga **para a AWS** (rampa até 5.000 TPS, p99 < 100 ms). Não rode como prova de 5.000 TPS no Mac: o cenário exige infraestrutura dimensionada e contratos de teste; o arquivo já tem extensão `.js` |

---

## 5. Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `o Docker está instalado mas não está rodando` | Docker Desktop fechado | abra o Docker Desktop e espere "Engine running" |
| `port is already allocated` | outra aplicação na porta | libere a porta (seção 2) ou pare o outro container |
| `$'\r': command not found` | o arquivo ganhou quebras de linha do Windows (CRLF) | `sed -i 's/\r$//' local/*.sh` |
| `Permission denied` ao rodar `./local/subir.sh` | o ZIP não guarda a permissão de execução | use `bash local/subir.sh` |
| Terraform falha com "already exists" ou "not found" | o LocalStack foi reiniciado e o estado do Terraform ficou velho | `bash local/derrubar.sh` e depois `bash local/subir.sh` |
| Passo 0 do demo não mostra a posição | primeira execução da Lambda Java (baixa a imagem do runtime) | rode o `demo.sh` de novo; veja `docker compose -f local/docker-compose.yml logs localstack` |
| Passo 8 sem linhas | a Contabilização ainda está assumindo o shard do Kinesis | espere 1 minuto e rode `docker compose -f local/docker-compose.yml logs contabilizacao` |
| LocalStack pede `LOCALSTACK_AUTH_TOKEN` | a imagem escolhida exige autenticação | obtenha token na conta LocalStack, exporte `LOCALSTACK_AUTH_TOKEN` e configure `LOCALSTACK_IMAGE` (imagem suportada no seu plano); não compartilhe o token |
| Maven ou Docker com erro de certificado (`PKIX`, `x509`) | rede corporativa com inspeção TLS | rode numa rede sem proxy, ou importe a CA corporativa no Java e no Docker |
| Mac com Apple Silicon (M1–M4) | — | funciona: as imagens são multi-arquitetura e as Lambdas rodam na arquitetura da máquina (`LAMBDA_IGNORE_ARCHITECTURE`) |

---

## 6. Onde está cada coisa

| Pasta | Conteúdo |
|---|---|
| `servicos/` | os 3 microsserviços (Java 21, Spring Boot, hexagonal) e o `Dockerfile` |
| `funcoes/` | as Lambdas (relay do outbox, ACL do legado, reconciliador, stubs) |
| `bibliotecas/` | `Dinheiro` (shared kernel) e os CloudEvents (published language) |
| `contracts/` | OpenAPI, AsyncAPI e JSON Schemas (fonte da verdade dos contratos) |
| `infra/terraform/` | módulos e ambientes `prod` (AWS) e `local` (LocalStack) |
| `local/` | `docker-compose.yml`, `subir.sh`, `demo.sh`, `derrubar.sh` |
| `docs/` | visão da solução, arquitetura, domínio, eventos, concorrência, custos, ADRs e diagramas |

Para entender a solução, comece por `docs/visao-da-solucao.md` e pelo pôster `docs/diagramas/poster-solucao.png`.

---

## 7. Correções C01–C12 e testes adicionais (22/10/2026)

**Somente depois de terminar os ajustes no repositório, execute estes testes no Mac.**
O assistente não executou Maven, Terraform ou Docker na revisão; todas as etapas abaixo são
**procedimentos**, não resultados previamente aprovados.

```bash
# Verificações estáticas sem acessar AWS
python3 local/verificar-estatico.py
python3 docs/custos/sensibilidade.py
mvn clean verify
mvn verify -Pintegracao

# Pré-validação Terraform. Não faz apply em AWS.
terraform -chdir=infra/terraform/ambientes/prod init -backend=false
terraform -chdir=infra/terraform/ambientes/prod validate

# Inicialização completa LOCAL (inclui terraform apply apenas no LocalStack!)
bash local/subir.sh
bash local/demo.sh
# Alternativamente, executar apenas o ciclo de reserva isolado:
bash local/demo-ciclo-reserva.sh
bash local/derrubar.sh
```

**Ciclo AmountBlock (RESERVA/CAPTURA/LIBERACAO):** o teste `demo-ciclo-reserva.sh`
publica um registro de contrato no core simulado, faz os três comandos via API,
e envia diretamente à SQS `reservas-a-expirar` uma mensagem **que simula**
a entrega do EventBridge Scheduler. **Não testa Scheduler AWS real.**
Se `demo.sh` parar no ciclo, execute o comando isolado e examine logs.

**Recuperação após TTL:** consulte [runbook](docs/runbooks/replay-evento-apos-ttl.md).
O script `scripts/recuperar-evento-trilha.py` faz dry-run sem publicar;
somente `--enviar` faz PutRecord e requer trilha original/ID e autorização de operador.

**LocalStack configurável:**

```bash
# Padrão 4.0 do ZIP (compatibilidade legada); pode não suportar recursos atuais.
export LOCALSTACK_IMAGE=localstack/localstack:4.0

# Para imagem atual, cheque a matriz de serviços do seu plano e use token.
# export LOCALSTACK_IMAGE=localstack/localstack:<VERSAO_VALIDADA>
# export LOCALSTACK_AUTH_TOKEN=<TOKEN_PRIVADO>
docker compose -f local/docker-compose.yml config --quiet
```

Os recursos do Compose estão expostos somente em **127.0.0.1**, não na rede LAN.
Nunca execute `terraform apply` no diretório `infra/terraform/ambientes/prod`
sem autorização explícita, revisão de custos/quotas e credenciais controladas.

**Ponte EventBridge para transações:** disponível via feature flag
`habilitar_eventbridge_transacoes` no Terraform de produção, **OFF por padrão**.
Não integra o cenário local sem licenciamento/provisionamento e validação própria.

**Novas evidências pendentes:** DLQ de entrega do Scheduler, DLQ de processamento
SQS, compensação depois de timeout, replay de eventos acima de 24h, schema de
CAPTURA/LIBERACAO e event bridge de negócio. Veja
[STATUS-FASES.md](docs/STATUS-FASES.md).
