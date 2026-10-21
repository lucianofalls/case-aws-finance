// Teste de carga (k6). Cenários: rampa até o pico do PDF (5.000 TPS) e contenção (poucos contratos quentes).
// Local prova correção; a escala de 5k TPS só se mede na AWS (ver docs/custos.md e docs/arquitetura.md).
//   k6 run -e API=https://<api>/v1 -e TOKEN=... -e API_KEY=... tests/k6/autorizacao.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const API = __ENV.API || 'http://localhost:8080';
const CONTRATOS = Number(__ENV.CONTRATOS || 100000);

export const options = {
  scenarios: {
    rampa_ate_pico: {
      executor: 'ramping-arrival-rate',
      startRate: 100, timeUnit: '1s', preAllocatedVUs: 500, maxVUs: 5000,
      stages: [
        { target: 1500, duration: '5m' },  // média assumida
        { target: 5000, duration: '5m' },  // pico do PDF
        { target: 5000, duration: '10m' },
        { target: 0, duration: '2m' },
      ],
    },
    contencao: {
      executor: 'constant-arrival-rate',
      rate: 200, timeUnit: '1s', duration: '5m', preAllocatedVUs: 200, startTime: '22m',
      env: { CONTRATOS: '10' },             // 10 contratos quentes: exercita C1/C2 (402/409, nunca saldo negativo)
    },
  },
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(99)<100'], // NFR assumido (MEMORIA §1)
    'checks': ['rate>0.999'],
  },
};

export default function () {
  const contrato = `CTR-${Math.floor(Math.random() * Number(__ENV.CONTRATOS || CONTRATOS))}`;
  const resposta = http.post(`${API}/v1/contratos/${contrato}/autorizacoes`,
    JSON.stringify({ id_conta: '0001-123456', valor: 10.0, moeda: 'BRL', tipo_operacao: 'DEBITO' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': uuidv4(),
        'X-Client-Id': 'k6',
        ...(__ENV.TOKEN ? { Authorization: `Bearer ${__ENV.TOKEN}` } : {}),
        ...(__ENV.API_KEY ? { 'x-api-key': __ENV.API_KEY } : {}),
      },
    });
  check(resposta, { 'status do contrato (201/402/409/429)': (r) => [201, 402, 409, 429].includes(r.status) });
}
