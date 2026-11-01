import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const bearer = token => ({ 'Content-Type': 'application/json', Authorization: `Bearer ${token}` });

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

function login(base, email, password) {
  return http.post(`${base}/api/auth/login`, JSON.stringify({ email, password }), JSON_HEADERS).json('accessToken');
}

export const adminToken = base => login(base, 'admin@corebank.demo', __ENV.ADMIN_PASSWORD || 'admin-demo-only');

export function createFundedCustomer(base, openingBalance) {
  const email = `load-${uuidv4()}@example.com`;
  http.post(`${base}/api/customers`, JSON.stringify({ name: 'Load Test', email, password: 'password123' }), JSON_HEADERS);
  const token = login(base, email, 'password123');
  const account = http.post(`${base}/api/accounts`, JSON.stringify({ type: 'SAVINGS' }), { headers: bearer(token) })
    .json('accountNumber');
  if (openingBalance > 0) {
    http.post(`${base}/api/deposits`, JSON.stringify({ accountNumber: account, amount: openingBalance, description: 'seed' }),
      { headers: { ...bearer(token), 'Idempotency-Key': uuidv4() } });
  }
  return { token, account };
}
