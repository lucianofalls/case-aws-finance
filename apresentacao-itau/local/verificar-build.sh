#!/usr/bin/env bash
# Verificação manual sem AWS: build Java, contratos e schema Terraform.
# INTEGRACAO=1 exige Docker e executa os testes *IT; não cria infraestrutura AWS.
set -euo pipefail
cd "$(dirname "$0")/.."
MVN_BIN="${MVN_BIN:-mvn}"
TF_BIN="${TF_BIN:-terraform}"
"$MVN_BIN" -version
"$TF_BIN" version
python3 scripts/verificar-migracao-eks-rds.py
python3 local/verificar-estatico.py
"$MVN_BIN" -B -ntp clean verify
if [[ "${INTEGRACAO:-0}" == "1" ]]; then
  docker info >/dev/null
  "$MVN_BIN" -B -ntp verify -Pintegracao
fi
"$TF_BIN" -chdir=infra/terraform fmt -check -recursive
for ambiente in prod local; do
  "$TF_BIN" -chdir="infra/terraform/ambientes/$ambiente" init -backend=false -input=false
  "$TF_BIN" -chdir="infra/terraform/ambientes/$ambiente" validate
 done
# Este módulo só transforma arquivos: não contém provider nem recursos AWS.
"$TF_BIN" -chdir=infra/terraform/modulos/openapi-api-gateway init -backend=false -input=false
"$TF_BIN" -chdir=infra/terraform/modulos/openapi-api-gateway test
