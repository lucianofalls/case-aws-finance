#!/usr/bin/env bash
# Sobe o ambiente local completo: build → LocalStack/Postgres/Jaeger → Terraform (mesmos módulos) → serviços.
# Funciona em Linux, macOS e Windows (Git Bash). Nada vai para a AWS: o provider aponta para o LocalStack.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f local/docker-compose.yml)
TF=(terraform -chdir=infra/terraform/ambientes/local)

falhar() { printf '\n\033[1;31mERRO: %s\033[0m\n' "$*" >&2; exit 1; }
etapa() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

etapa "0/5 pré-requisitos"
command -v docker >/dev/null || falhar "Docker não encontrado (instale Docker Desktop, Rancher Desktop ou Docker Engine)"
docker info >/dev/null 2>&1 || falhar "o Docker está instalado mas não está rodando (abra o Docker Desktop e espere 'Engine running')"
docker compose version >/dev/null 2>&1 || falhar "plugin 'docker compose' (v2) não encontrado"
command -v java >/dev/null || falhar "Java não encontrado (precisa do JDK 21)"
java -version 2>&1 | grep -Eq 'version "21' || falhar "precisa do JDK 21 (encontrado: $(java -version 2>&1 | head -1))"
command -v mvn >/dev/null || falhar "Maven não encontrado (precisa do 3.9+)"
command -v terraform >/dev/null || falhar "Terraform não encontrado (precisa do 1.9+)"
command -v curl >/dev/null || falhar "curl não encontrado"
echo "ok: docker, java 21, maven, terraform, curl"

python3 local/token-demo.py --preparar

etapa "1/5 build e testes (Maven)"
mvn -q -B install

etapa "2/5 artefatos das Lambdas"
mkdir -p artefatos
for funcao in outbox-relay reconciliador corebanking-acl consumidores-stub; do
  cp "funcoes/${funcao}/target/${funcao}.jar" artefatos/
done

etapa "3/5 LocalStack, Postgres e Jaeger"
"${COMPOSE[@]}" up -d --wait localstack postgres jaeger

etapa "4/5 infraestrutura local (Terraform → LocalStack)"
# Estado do Terraform só vale para o LocalStack que está de pé: se ele foi recriado, o estado antigo é descartado.
if [[ -f infra/terraform/ambientes/local/terraform.tfstate ]] && \
   ! "${COMPOSE[@]}" exec -T localstack awslocal dynamodb describe-table --table-name autorizacoes >/dev/null 2>&1; then
  echo "LocalStack novo: descartando estado anterior do Terraform"
  rm -f infra/terraform/ambientes/local/terraform.tfstate infra/terraform/ambientes/local/terraform.tfstate.backup
fi
"${TF[@]}" init -input=false -no-color >/dev/null
"${TF[@]}" apply -input=false -auto-approve -no-color

etapa "5/5 serviços (mesma imagem do Fargate)"
"${COMPOSE[@]}" up -d --build limites autorizacao contabilizacao

aguardar() { # $1=nome $2=url
  for _ in $(seq 1 90); do
    curl -sf "$2" >/dev/null 2>&1 && { echo "ok: $1"; return 0; }
    sleep 2
  done
  "${COMPOSE[@]}" logs --tail 60 "$1" >&2
  falhar "$1 não ficou saudável em 3 min (logs acima)"
}
aguardar limites http://localhost:8082/actuator/health
aguardar autorizacao http://localhost:8080/actuator/health
aguardar contabilizacao http://localhost:8083/actuator/health

printf '\n\033[1;32mpronto\033[0m: API http://localhost:8080 · Limites http://localhost:8082 · Jaeger http://localhost:16686\n'
echo "próximo passo: local/demo.sh      (para desligar tudo: local/derrubar.sh)"
