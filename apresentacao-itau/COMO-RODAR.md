# Como rodar a solução numa máquina nova

Guia para subir a plataforma inteira **localmente**, sem conta AWS e sem pagar nada. Os serviços AWS são simulados
pelo **LocalStack** (edição Community 4.0, sem cadastro e sem token) e criados pelo **mesmo Terraform** da produção.

**Tempo:** a primeira execução leva de 10 a 20 minutos, porque baixa as dependências Maven, as imagens Docker e o
runtime Java das Lambdas. As seguintes levam de 2 a 4 minutos.

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
unzip itau-pod99-solucao.zip -d itau
cd itau/apresentacao-itau
```

Para passar pelo filtro de e-mail, os scripts (`.sh`) e o teste de carga (`.js`) vieram com `.txt` no fim do
nome. Devolva os nomes originais **uma vez**, antes de tudo:

```bash
find . \( -name "*.sh.txt" -o -name "*.js.txt" \) | while read -r f; do mv "$f" "${f%.txt}"; done
ls local/   # deve mostrar demo.sh, derrubar.sh, subir.sh
```

### 3.2 Subir tudo

```bash
bash local/subir.sh
```

O script faz, em ordem:

| Etapa | O que acontece | Se falhar |
|---|---|---|
| 0/5 | Confere Docker (rodando), Java 21, Maven, Terraform e curl | mostra exatamente o que falta |
| 1/5 | `mvn install`: compila os 9 módulos e roda **80 testes** | um teste falhou: veja a saída do Maven |
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
| 9 | Rastreio | abra http://localhost:16686 e procure por `autorizacao-service` |

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
| `k6 run -e API=... tests/k6/autorizacao.js` | teste de carga **para a AWS** (rampa até 5.000 TPS, p99 < 100 ms). Não rode localmente: os contratos do cenário não existem e a máquina não sustenta a carga. Renomeie o arquivo antes (ver 3.1) |

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
| LocalStack pede `LOCALSTACK_AUTH_TOKEN` | a imagem foi trocada por uma versão nova (a partir de 2026 o token é obrigatório) | mantenha `localstack/localstack:4.0` no `local/docker-compose.yml` |
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
