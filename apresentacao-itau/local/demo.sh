#!/usr/bin/env bash
# E2E com asserções: qualquer divergência encerra com status diferente de zero.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 local/verificar-demo.py "$@"
