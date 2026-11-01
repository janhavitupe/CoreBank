// Success criterion: p95 transfer latency under 300 ms at 100 requests/s.
// Each virtual customer has its own account pair, so this measures the platform rather than one contended row.
//   k6 run -e BASE_URL=http://localhost:8080 --summary-export=load-tests/results.json load-tests/transfer-load.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { bearer, createFundedCustomer } from './lib.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 100);
const PAIRS = 20;

export const options = {
  setupTimeout: '5m',
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 60, maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_duration{name:transfer}': ['p(95)<300'],
    'http_req_failed{name:transfer}': ['rate<0.01'],
  },
};

export function setup() {
  const pairs = [];
  for (let i = 0; i < PAIRS; i++) {
    const a = createFundedCustomer(BASE, 100000000);
    const b = createFundedCustomer(BASE, 0);
    pairs.push({ token: a.token, from: a.account, to: b.account });
  }
  return { pairs };
}

export default function ({ pairs }) {
  const pair = pairs[__ITER % pairs.length];
  const res = http.post(`${BASE}/api/transfers`,
    JSON.stringify({ fromAccount: pair.from, toAccount: pair.to, amount: 1, description: 'load test' }),
    { headers: { ...bearer(pair.token), 'Idempotency-Key': uuidv4() }, tags: { name: 'transfer' } });
  check(res, { 'transfer completed': r => r.status === 201 });
}
