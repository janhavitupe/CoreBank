// Success criterion: zero duplicate debits across 1,000 transfers replayed with random retries.
//   k6 run -e BASE_URL=http://localhost:8080 load-tests/replay-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { adminToken, bearer, createFundedCustomer } from './lib.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TRANSFERS = Number(__ENV.TRANSFERS || 1000);
const AMOUNT = 1;
const OPENING_BALANCE = 1000000;

const retriesSent = new Counter('retries_sent');
const replaysServed = new Counter('replays_served');

export const options = {
  scenarios: {
    replay: { executor: 'shared-iterations', vus: 10, iterations: TRANSFERS, maxDuration: '10m' },
  },
  thresholds: { checks: ['rate==1'] },
};

export function setup() {
  const a = createFundedCustomer(BASE, OPENING_BALANCE);
  const b = createFundedCustomer(BASE, 0);
  return { token: a.token, from: a.account, to: b.account, toToken: b.token };
}

export default function (data) {
  const key = uuidv4();
  const params = { headers: { ...bearer(data.token), 'Idempotency-Key': key } };
  const body = JSON.stringify({ fromAccount: data.from, toAccount: data.to, amount: AMOUNT, description: 'replay test' });

  const first = http.post(`${BASE}/api/transfers`, body, params);
  check(first, { 'first attempt accepted': r => r.status === 201 || r.status === 202 });

  // Between 1 and 3 extra sends of the identical request, as a client would after a timeout.
  const retries = 1 + Math.floor(Math.random() * 3);
  for (let i = 0; i < retries; i++) {
    retriesSent.add(1);
    const retry = http.post(`${BASE}/api/transfers`, body, params);
    if (retry.headers['Idempotent-Replayed'] === 'true') replaysServed.add(1);
    check(retry, {
      'retry returns the same transaction': r => r.json('id') === first.json('id'),
    });
  }
}

export function teardown(data) {
  const expected = OPENING_BALANCE - TRANSFERS * AMOUNT;
  let balance;
  // Pending transfers finish through the recovery job, so allow it a short time.
  for (let i = 0; i < 30; i++) {
    balance = http.get(`${BASE}/api/accounts/${data.from}/balance`, { headers: bearer(data.token) }).json('balance');
    if (Number(balance) === expected) break;
    sleep(1);
  }
  const report = http.get(`${BASE}/api/admin/reconciliation`, { headers: bearer(adminToken(BASE)) }).json();
  console.log(`Balance ${balance}, expected ${expected}. Reconciliation: ${JSON.stringify(report)}`);
  if (Number(balance) !== expected || report.balanceMismatches !== 0 || report.duplicateEntries !== 0) {
    throw new Error('Replay test FAILED: money moved incorrectly');
  }
  console.log('Replay test PASSED: zero duplicate debits');
}
