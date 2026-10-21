#!/usr/bin/env bash
# Desliga o ambiente local e apaga os dados (LocalStack não persiste; o estado do Terraform local vai junto).
# Os containers das Lambdas são criados e removidos pelo próprio LocalStack.
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose -f local/docker-compose.yml down -v --remove-orphans
rm -f infra/terraform/ambientes/local/terraform.tfstate infra/terraform/ambientes/local/terraform.tfstate.backup
echo "ambiente local removido"
