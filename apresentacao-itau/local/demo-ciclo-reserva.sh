#!/usr/bin/env bash
# Cenário C05/C11: RESERVA -> CAPTURA parcial -> LIBERACAO parcial -> EXPIRAÇÃO.
# Usa contrato isolado. LocalStack simula somente entrega da SQS, NÃO o Scheduler real.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f local/docker-compose.yml)
API=${API:-http://localhost:8080}
LIMITES=${LIMITES:-http://localhost:8082}
CONTRATO="CTR-CICLO-$RANDOM-$RANDOM"
CONTA="0001-123456"
CLIENTE="canal-demo-ciclo"
RETORNO=$(mktemp)
trap 'rm -f "$RETORNO"' EXIT

uuid() {
  if command -v uuidgen >/dev/null 2>&1; then uuidgen | tr 'A-Z' 'a-z'
  else python3 -c 'import uuid; print(uuid.uuid4())'
  fi
}

postar() { # tipo, valor, original(optional), esperado
  local tipo="$1" valor="$2" original="${3:-}" esperado="$4"
  local corpo="{\"id_conta\":\"$CONTA\",\"valor\":$valor,\"moeda\":\"BRL\",\"tipo_operacao\":\"$tipo\""
  if [[ -n "$original" ]]; then
    corpo+=",\"metadata\":{\"id_autorizacao_original\":\"$original\"}"
  fi
  corpo+='}'
  local status
  status=$(curl -sS --max-time 30 -o "$RETORNO" -w '%{http_code}' \
    -X POST "$API/v1/contratos/$CONTRATO/autorizacoes" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
    -H "X-Client-Id: $CLIENTE" -d "$corpo")
  printf '%s R$ %s -> HTTP %s\n' "$tipo" "$valor" "$status"
  cat "$RETORNO"; echo
  if [[ "$status" != "$esperado" ]]; then
    echo "Falha no roteiro: aguardava HTTP $esperado para $tipo" >&2
    exit 1
  fi
}

printf '\n==> Registrando novo contrato no core simulado: %s\n' "$CONTRATO"
REGISTRO=$(printf '%-20s%-12s%s%015d%s%010d%s' "$CONTRATO" "$CONTA" 'CP' 100000 'BRL' 1 "$(date +%Y%m%d%H%M%S)")
"${COMPOSE[@]}" exec -T localstack awslocal sqs send-message \
  --queue-url http://localhost:4566/000000000000/corebanking-contratos \
  --message-body "$REGISTRO" >/dev/null
for _ in $(seq 1 90); do
  curl -fsS "$LIMITES/v1/contratos/$CONTRATO/posicao" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$LIMITES/v1/contratos/$CONTRATO/posicao"; echo
# APENAS DEMO LOCAL: provisão simulada de entitlement por fonte confiável.
# Em produção, um fluxo administrativo autenticado/ACL de identidade deve alimentar esta tabela.
"${COMPOSE[@]}" exec -T localstack awslocal dynamodb put-item \
  --table-name permissoes-contratos --item "{\"id_cliente\":{\"S\":\"canal-demo-ciclo\"},\"id_contrato\":{\"S\":\"$CONTRATO\"},\"id_conta\":{\"S\":\"0001-123456\"},\"ativo\":{\"BOOL\":true}}" >/dev/null

postar RESERVA 100.00 '' 201
RESERVA_ID=$(python3 - "$RETORNO" <<'PY'
import json,sys
obj=json.load(open(sys.argv[1]))
valor=obj.get('id_autorizacao') or obj.get('idAutorizacao')
if not valor: raise SystemExit('201 sem id_autorizacao: verifique response/schema')
print(valor)
PY
)
echo "Reserva original: $RESERVA_ID"
postar CAPTURA 30.00 "$RESERVA_ID" 201
postar LIBERACAO 20.00 "$RESERVA_ID" 201
printf '\n==> Antes da expiração: disponível 920.00, bloqueado 50.00 e utilizado 30.00\n'
curl -fsS "$LIMITES/v1/contratos/$CONTRATO/posicao"; echo

printf '\n==> SIMULANDO entrega do Scheduler via SQS: libera os R$ 50 restantes\n'
"${COMPOSE[@]}" exec -T localstack awslocal sqs send-message \
  --queue-url http://localhost:4566/000000000000/reservas-a-expirar \
  --message-body "{\"id_autorizacao\":\"$RESERVA_ID\",\"id_contrato\":\"$CONTRATO\"}" >/dev/null

for _ in $(seq 1 60); do
  POSICAO=$(curl -fsS "$LIMITES/v1/contratos/$CONTRATO/posicao" || true)
  if printf '%s' "$POSICAO" | python3 -c 'import sys,json; v=json.load(sys.stdin); print(v.get("saldo_bloqueado",v.get("saldoBloqueado"))=="0.00")' 2>/dev/null | grep -qx 'True'; then
    echo "$POSICAO"
    echo 'OK: saldo bloqueado zerou após expiração simulada.'
    exit 0
  fi
  sleep 2
done
printf '%s\n' "$POSICAO"
echo 'Falha: bloqueio não liberado. Investigue listener SQS e logs da Autorização.' >&2
exit 1
