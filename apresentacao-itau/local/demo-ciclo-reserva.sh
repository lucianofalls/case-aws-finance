#!/usr/bin/env bash
# Ciclo isolado: usa o mesmo verificador e espera o vencimento persistido da reserva.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 local/verificar-demo.py --somente-reserva "$@"
