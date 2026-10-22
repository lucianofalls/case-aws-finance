#!/usr/bin/env bash
# Roteiro de demo: cada passo prova um requisito do PDF (§2.3, §2.4, §3.3). Requer local/subir.sh.
# Funciona em Linux, macOS e Windows (Git Bash).
set -uo pipefail

API=${API:-http://localhost:8080}
LIMITES=${LIMITES:-http://localhost:8082}
CONTRATO=CTR-DEMO-$RANDOM
CLIENTE=canal-demo
COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yml")
RESPOSTA=$(mktemp)

passo() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }

uuid() { # portável: Linux, macOS, Git Bash
  if command -v uuidgen >/dev/null 2>&1; then uuidgen | tr 'A-Z' 'a-z'
  elif [[ -r /proc/sys/kernel/random/uuid ]]; then cat /proc/sys/kernel/random/uuid
  elif command -v python3 >/dev/null 2>&1; then python3 -c 'import uuid; print(uuid.uuid4())'
  elif command -v python >/dev/null 2>&1; then python -c 'import uuid; print(uuid.uuid4())'
  else powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r'
  fi
}

autorizar() { # $1=Idempotency-Key $2=corpo
  curl -s -o "$RESPOSTA" -w "HTTP %{http_code}\n" -X POST "$API/v1/contratos/$CONTRATO/autorizacoes" \
    -H "Content-Type: application/json" -H "Idempotency-Key: $1" -H "X-Client-Id: $CLIENTE" -d "$2"
  cat "$RESPOSTA"; echo
}

debito() { echo "{\"id_conta\":\"0001-123456\",\"valor\":$1,\"moeda\":\"BRL\",\"tipo_operacao\":\"DEBITO\"}"; }

passo "0. Core banking publica o contrato (registro posicional) → ACL → EventBridge → SQS → Limites"
REGISTRO=$(printf '%-20s%-12s%s%015d%s%010d%s' "$CONTRATO" "0001-123456" "CP" 100000 "BRL" 1 "$(date +%Y%m%d%H%M%S)")
"${COMPOSE[@]}" exec -T localstack \
  awslocal sqs send-message --queue-url http://localhost:4566/000000000000/corebanking-contratos --message-body "$REGISTRO" >/dev/null
echo "aguardando o limite chegar ao Limites (a 1ª execução de uma Lambda Java pode levar ~1 min)..."
for _ in $(seq 1 90); do
  curl -sf "$LIMITES/v1/contratos/$CONTRATO/posicao" >/dev/null && break; sleep 2
done
curl -s "$LIMITES/v1/contratos/$CONTRATO/posicao"; echo "   (limite R\$ 1.000,00)"
# APENAS DEMO LOCAL: provisão simulada de entitlement por fonte confiável.
# Em produção, um fluxo administrativo autenticado/ACL de identidade deve alimentar esta tabela.
"${COMPOSE[@]}" exec -T localstack awslocal dynamodb put-item \
  --table-name permissoes-contratos --item "{\"id_cliente\":{\"S\":\"canal-demo\"},\"id_contrato\":{\"S\":\"$CONTRATO\"},\"id_conta\":{\"S\":\"0001-123456\"},\"ativo\":{\"BOOL\":true}}" >/dev/null

CHAVE=$(uuid)
passo "1. Autorização aprovada → 201 com saldo_reservado"
autorizar "$CHAVE" "$(debito 100.00)"

passo "2. Mesma Idempotency-Key e mesmo payload → 200, resultado original (sem novo débito)"
autorizar "$CHAVE" "$(debito 100.00)"

passo "3. Mesma Idempotency-Key com outro payload → 422"
autorizar "$CHAVE" "$(debito 999.00)"

passo "4. Valor acima do disponível → 402 Problem Details"
autorizar "$(uuid)" "$(debito 5000.00)"

passo "5. 50 autorizações simultâneas de R\$ 20 no mesmo contrato (disponível R\$ 900) → nunca ultrapassa"
CHAVES=()
for _ in $(seq 1 50); do CHAVES+=("$(uuid)"); done
for chave in "${CHAVES[@]}"; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$API/v1/contratos/$CONTRATO/autorizacoes" \
    -H "Content-Type: application/json" -H "Idempotency-Key: $chave" \
    -H "X-Client-Id: $CLIENTE" -d "$(debito 20.00)" &
done | sort | uniq -c
wait
curl -s "$LIMITES/v1/contratos/$CONTRATO/posicao"; echo "   (disponível nunca negativo; 201 ≤ 45)"

passo "6. Payload inválido → 422"
autorizar "$(uuid)" '{"id_conta":"","valor":-1,"moeda":"real","tipo_operacao":"SAQUE"}'

passo "7. Limites fora do ar → 503 fail-fast (circuit breaker) com Retry-After"
"${COMPOSE[@]}" stop limites >/dev/null
for _ in $(seq 1 25); do autorizar "$(uuid)" "$(debito 1.00)" >/dev/null; done
curl -s -D - -o "$RESPOSTA" -X POST "$API/v1/contratos/$CONTRATO/autorizacoes" \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(uuid)" -H "X-Client-Id: $CLIENTE" \
  -d "$(debito 1.00)" | grep -iE "^HTTP|^retry-after"
cat "$RESPOSTA"; echo
"${COMPOSE[@]}" start limites >/dev/null

passo "8. Eventos: TransacaoAutorizada no Kinesis → contabilização (partidas dobradas no Postgres)"
CONSULTA="SELECT r.id_contrato, l.natureza, l.conta_codigo, l.valor FROM lancamento l JOIN registro_contabil r USING (fato_id) WHERE r.id_contrato = '$CONTRATO'"
echo "aguardando a contabilização consumir o Kinesis..."
for _ in $(seq 1 60); do
  TOTAL=$("${COMPOSE[@]}" exec -T postgres psql -U contabilizacao -tAc "SELECT count(*) FROM ($CONSULTA) t" 2>/dev/null | tr -d '[:space:]')
  [[ "${TOTAL:-0}" -gt 0 ]] && break; sleep 3
done
"${COMPOSE[@]}" exec -T postgres psql -U contabilizacao -c "$CONSULTA LIMIT 6;"

passo "9. Ciclo BIAN AmountBlock: RESERVA → CAPTURA → LIBERACAO → expiração simulada (contrato isolado)"
bash "$(dirname "$0")/demo-ciclo-reserva.sh"

passo "10. Traces ponta a ponta: http://localhost:16686 (serviço autorizacao-service)"
echo "429 (usage plan), Cognito e WAF são do API Gateway: estão em infra/terraform/ambientes/prod (não sobem no LocalStack)."
rm -f "$RESPOSTA"
