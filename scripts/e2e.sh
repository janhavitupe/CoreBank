#!/usr/bin/env bash
# End-to-end proof of the platform's guarantees against a running Kubernetes deployment.
# Needs curl, jq, kubectl and a port-forward from the gateway to localhost:8080.
set -u

BASE=${BASE_URL:-http://localhost:8080}
NS=corebank
ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin-demo-only}
failures=0

# GitHub shows only 10 notices per step, so the run summary carries the full list.
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}
pass() { echo "::notice title=E2E PASS::$1"; echo "- PASS: $1" >> "$SUMMARY"; }
fail() { echo "::error title=E2E FAIL::$1"; echo "- **FAIL: $1**" >> "$SUMMARY"; failures=$((failures + 1)); }
expect() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected $3, got $2)"; fi; }

post() { # path token key body
  curl -s -m 15 -X POST "$BASE$1" -H 'Content-Type: application/json' \
    ${2:+-H "Authorization: Bearer $2"} ${3:+-H "Idempotency-Key: $3"} -d "$4"
}
code() { # path token key body
  curl -s -m 15 -o /dev/null -w '%{http_code}' -X POST "$BASE$1" -H 'Content-Type: application/json' \
    ${2:+-H "Authorization: Bearer $2"} ${3:+-H "Idempotency-Key: $3"} -d "$4"
}
get() { curl -s -m 15 "$BASE$1" -H "Authorization: Bearer $2"; }
# jq keeps a number's original text, so "+ 0" turns 7500.0000 into 7500 for comparison and shell arithmetic.
balance() { get "/api/accounts/$1/balance" "$2" | jq -r '(.balance | tonumber) + 0'; }
key() { cat /proc/sys/kernel/random/uuid; }

customer() { # name -> sets TOKEN and ACCOUNT
  local email="$1-$(key)@example.com"
  post /api/customers "" "" "{\"name\":\"$1\",\"email\":\"$email\",\"password\":\"password123\"}" > /dev/null
  TOKEN=$(post /api/auth/login "" "" "{\"email\":\"$email\",\"password\":\"password123\"}" | jq -r .accessToken)
  ACCOUNT=$(post /api/accounts "$TOKEN" "" '{"type":"SAVINGS"}' | jq -r .accountNumber)
}

transfer_body() { echo "{\"fromAccount\":\"$ACCOUNT_A\",\"toAccount\":\"$ACCOUNT_B\",\"amount\":$1,\"description\":\"$2\"}"; }

wait_for() { # seconds description command...
  local limit=$1 what=$2; shift 2
  for _ in $(seq 1 "$limit"); do
    if "$@"; then pass "$what"; return 0; fi
    sleep 1
  done
  fail "$what (timed out after ${limit}s)"
  return 1
}

# Security
expect "Request without a token is rejected" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/accounts")" 401
expect "Invalid token is rejected" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/accounts" -H 'Authorization: Bearer forged')" 401

customer asha; TOKEN_A=$TOKEN; ACCOUNT_A=$ACCOUNT
customer ravi; TOKEN_B=$TOKEN; ACCOUNT_B=$ACCOUNT
[ "$ACCOUNT_A" != "null" ] && [ -n "$ACCOUNT_A" ] && pass "Register, log in and open an account through the gateway" \
  || fail "Register, log in and open an account through the gateway"

FORGED=$(curl -s "$BASE/api/accounts" -H "Authorization: Bearer $TOKEN_A" -H "X-Customer-Id: 00000000-0000-0000-0000-000000000001" | jq -r '.[0].accountNumber')
expect "Forged X-Customer-Id header is replaced by the verified identity" "$FORGED" "$ACCOUNT_A"
expect "Customer cannot read another customer's balance" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/accounts/$ACCOUNT_B/balance" -H "Authorization: Bearer $TOKEN_A")" 403
expect "Customer cannot spend from another customer's account" \
  "$(code /api/transfers "$TOKEN_B" "$(key)" "$(transfer_body 1 theft)")" 422
expect "Non-admin cannot freeze an account" "$(code "/api/accounts/$ACCOUNT_A/freeze" "$TOKEN_A" "" '{}')" 403

# Money movement
expect "Deposit completes" "$(code /api/deposits "$TOKEN_A" "$(key)" "{\"accountNumber\":\"$ACCOUNT_A\",\"amount\":10000,\"description\":\"salary\"}")" 201

KEY=$(key)
FIRST=$(post /api/transfers "$TOKEN_A" "$KEY" "$(transfer_body 2500 'Grocery shopping')" | jq -r .id)
SECOND=$(post /api/transfers "$TOKEN_A" "$KEY" "$(transfer_body 2500 'Grocery shopping')" | jq -r .id)
expect "Retry with the same key returns the same transaction" "$SECOND" "$FIRST"
expect "Retry did not debit twice" "$(balance "$ACCOUNT_A" "$TOKEN_A")" 7500
expect "Recipient was credited once" "$(balance "$ACCOUNT_B" "$TOKEN_B")" 2500
expect "Same key with a different amount is rejected" "$(code /api/transfers "$TOKEN_A" "$KEY" "$(transfer_body 1 changed)")" 422
expect "Insufficient funds fails cleanly" "$(code /api/transfers "$TOKEN_A" "$(key)" "$(transfer_body 999999 toomuch)")" 422
expect "Balance read straight after a transfer is fresh" "$(balance "$ACCOUNT_A" "$TOKEN_A")" 7500

ADMIN=$(post /api/auth/login "" "" "{\"email\":\"admin@corebank.demo\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .accessToken)
post "/api/accounts/$ACCOUNT_B/freeze" "$ADMIN" "" '{}' > /dev/null
expect "Frozen account cannot receive money" "$(code /api/transfers "$TOKEN_A" "$(key)" "$(transfer_body 1 frozen)")" 422
post "/api/accounts/$ACCOUNT_B/unfreeze" "$ADMIN" "" '{}' > /dev/null

# Events
notification_count() { get /api/notifications "$1" | jq '[.[] | select(.channel == "IN_APP")] | length'; }
has_notifications() { [ "$(notification_count "$TOKEN_A")" -ge "$1" ] 2>/dev/null; }
wait_for 60 "Kafka delivers transfer notifications" has_notifications 2

BEFORE=$(notification_count "$TOKEN_A")
kubectl -n $NS scale deployment/notification-service --replicas=0 > /dev/null
kubectl -n $NS wait --for=delete pod -l app=notification-service --timeout=60s > /dev/null 2>&1
expect "Transfer completes while Notification Service is down" \
  "$(code /api/transfers "$TOKEN_A" "$(key)" "$(transfer_body 100 'while notifications down')")" 201
kubectl -n $NS scale deployment/notification-service --replicas=1 > /dev/null
kubectl -n $NS rollout status deployment/notification-service --timeout=180s > /dev/null
wait_for 90 "Queued event is delivered after Notification Service recovers" has_notifications $((BEFORE + 1))

kubectl -n $NS exec kafka-0 -- bash -c \
  'echo "this is not json" | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic customer.events' > /dev/null 2>&1
DEAD=$(kubectl -n $NS exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic customer.events.DLT --from-beginning --max-messages 1 --timeout-ms 45000 2>/dev/null)
expect "Poison message lands in the dead-letter topic after retries" "$DEAD" "this is not json"

# Crash recovery: kill the service after money moved and before the outcome is recorded
BEFORE_A=$(balance "$ACCOUNT_A" "$TOKEN_A")
expect "Crash switch arms" "$(code /api/admin/chaos/crash-next-settlement "$ADMIN" "" '{}')" 200
KEY=$(key)
code /api/transfers "$TOKEN_A" "$KEY" "$(transfer_body 500 'crash test')" > /dev/null
sleep 3
kubectl -n $NS rollout status deployment/transaction-service --timeout=180s > /dev/null
recovered() {
  local body status
  body=$(post /api/transfers "$TOKEN_A" "$KEY" "$(transfer_body 500 'crash test')")
  status=$(echo "$body" | jq -r '.status // empty')
  [ "$status" = "COMPLETED" ] || [ "$status" = "FAILED" ]
}
wait_for 120 "Crashed transfer is resolved by recovery" recovered
expect "Crashed transfer debited exactly once" "$(balance "$ACCOUNT_A" "$TOKEN_A")" "$((BEFORE_A - 500))"
expect "Transaction Service restarted after the crash" \
  "$(kubectl -n $NS get pod -l app=transaction-service -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}' | awk '{print ($1 > 0) ? "yes" : "no"}')" yes

# Self-healing
kubectl -n $NS delete pod -l app=account-service --grace-period=0 --force > /dev/null 2>&1
sleep 2
kubectl -n $NS rollout status deployment/account-service --timeout=180s > /dev/null
healed() { [ "$(code /api/transfers "$TOKEN_A" "$(key)" "$(transfer_body 1 'after pod kill')")" = "201" ]; }
wait_for 90 "Service self-heals after a forced pod kill" healed

# Reconciliation
RECON=$(get /api/admin/reconciliation "$ADMIN")
echo "Reconciliation: $RECON"
expect "Ledger matches every balance" "$(echo "$RECON" | jq .balanceMismatches)" 0
expect "No duplicate ledger entries" "$(echo "$RECON" | jq .duplicateEntries)" 0
expect "Every transfer's debit equals its credit" "$(echo "$RECON" | jq .unbalancedTransfers)" 0

echo "::notice title=E2E SUMMARY::$failures failure(s)"
echo "**$failures failure(s)**" >> "$SUMMARY"
exit "$failures"
