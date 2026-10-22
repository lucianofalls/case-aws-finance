#!/usr/bin/env bash
# Entrada única. Preserva containers após sucesso/falha para inspeção; não acessa AWS.
set -euo pipefail
cd "$(dirname "$0")/.."
export PYTHONUNBUFFERED=1
mkdir -p local/evidencias
LOG="local/evidencias/aceite-$(date +%Y%m%d-%H%M%S).log"
{
  echo "Execução local iniciada em $(date -u +%FT%TZ)"
  git rev-parse HEAD
  trap 'status=$?; if (( status != 0 )); then docker compose -f local/docker-compose.yml ps; docker compose -f local/docker-compose.yml logs --no-color --tail 80; fi; exit "$status"' EXIT
  bash local/subir.sh
  if [[ "${INTEGRACAO:-0}" == "1" ]]; then
    mvn -B -ntp verify -Pintegracao
  fi
  bash local/demo.sh
} 2>&1 | tee "$LOG"
echo "Evidência: $LOG"
echo "Para remover os dados locais: bash local/derrubar.sh"
