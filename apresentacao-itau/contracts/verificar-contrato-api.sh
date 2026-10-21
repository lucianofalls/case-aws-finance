#!/usr/bin/env bash
# Barra breaking change em contrato de API REST — o equivalente, para OpenAPI, da regra FULL do Glue Schema
# Registry nos eventos. O API Gateway aceita qualquer definição nova; a proteção fica no pipeline.
#
# Uso:   contracts/verificar-contrato-api.sh <contrato-base.yaml> <contrato-novo.yaml>
# No CI: base = versão publicada (ex.: git show origin/main:contracts/openapi/autorizacoes-v1.yaml > /tmp/base.yaml)
#        novo = contracts/openapi/autorizacoes-v1.yaml do pull request
#
# Breaking change detectada → exit ≠ 0 → o PR não segue. Mudança incompatível de verdade = nova rota /v2
# (o arquivo -v1 continua valendo para quem ainda não migrou; ver docs/visao-da-solucao.md §5.6).
#
# Ferramenta: oasdiff (open source, Apache-2.0). Usa o binário se existir; senão, a imagem Docker oficial
# (os dois arquivos precisam estar dentro do diretório atual).
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "uso: $0 <contrato-base.yaml> <contrato-novo.yaml>" >&2
  exit 2
fi

base="$1"
novo="$2"

if command -v oasdiff >/dev/null 2>&1; then
  oasdiff breaking "$base" "$novo" --fail-on ERR
else
  docker run --rm -v "$PWD":/contratos -w /contratos tufin/oasdiff breaking "$base" "$novo" --fail-on ERR
fi

echo "ok: nenhuma breaking change entre $base e $novo"
