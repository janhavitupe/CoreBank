# CoreBank

A cloud-native retail banking backend: Spring Boot microservices behind a secured API gateway, built to move money correctly when networks fail, clients retry and services crash mid-operation.

## Architecture

```
                         Demo page / API clients
                                   |
                     API Gateway  (JWT, rate limit, correlation ID)
                                   |
      +-------------+--------------+----------------+----------------+
      |             |              |                |                |
  Customer       Account  <--- Transaction     Notification     AI Insights
  Service        Service  sync   Service          Service         Service
      |             |              |                ^                ^
      |             |              |                |                |
      +------- outbox ---> Apache Kafka topics -----+----------------+
```

Each service owns its own PostgreSQL schema. Only the money-moving path is synchronous (Transaction to Account), so a slow notification or a Gemini outage can never block a transfer.

## How a transfer stays correct

The proposal's flow left a gap: Account Service and Transaction Service commit to different databases, so a crash between them could move money with no record, and a retry could then debit again. CoreBank closes that gap in three steps:

1. **Record intent.** Transaction Service commits a `PENDING` transaction and the idempotency key before any money moves.
2. **Move money idempotently.** Account Service writes the balances and `ledger_entries` in one commit. A unique constraint on `(transaction_id, entry_type)` means a repeat cannot debit twice. Optimistic locking guards the balance.
3. **Record the outcome.** The status change and the outbox event commit together. A relay publishes the event to Kafka.

If the service crashes after step 2, `RecoveryJob` finds the `PENDING` row and repeats the posting, which safely returns the earlier result.

| Failure | What happens |
|---|---|
| Client retries | Same key returns the stored result; no money moves |
| Timeout calling Account Service | Stays `PENDING`; recovery resolves it |
| Crash after money moved | Recovery completes the transfer with no second debit |
| Notification Service down | Transfers still complete; events wait in Kafka |
| Duplicate Kafka delivery | Consumers ignore known event IDs |
| Poison message | 3 retries with backoff, then a dead-letter topic |
| Redis down | Balance reads fall back to PostgreSQL |
| Gemini down | Category falls back to `other`; money flow is untouched |

## Tech stack

Java 21, Spring Boot 3, Spring Data JPA, Spring Cloud Gateway, PostgreSQL, Flyway, Apache Kafka, Redis, OpenAPI and Swagger UI, JUnit 5, Mockito, Testcontainers, k6, Docker, Docker Compose, Kubernetes, Azure AKS, Azure Container Registry, GitHub Actions, Prometheus, Grafana, Elasticsearch, Logstash, Kibana, Gemini API and Google ADK.

Added for local use: Mailpit (captures email), Alertmanager (routes alerts) and kind (Kubernetes in CI).

## Run it

Requires JDK 21, Maven and Docker.

```bash
mvn -DskipTests package
docker compose up -d --build
```

Open http://localhost:8080 for the demo page. Sign in as admin with `admin@corebank.demo` and `admin-demo-only`; these are local demo values.

Optional tools, started separately to save memory:

```bash
docker compose --profile observability up -d   # Grafana :3000, Prometheus :9090
docker compose --profile elk up -d             # Kibana :5601
```

Set `GEMINI_API_KEY` before starting to enable the assistant.

## Tests

```bash
mvn verify                                             # Unit and Testcontainers integration tests
k6 run -e BASE_URL=http://localhost:8080 load-tests/replay-test.js      # 1,000 transfers with random retries
k6 run -e BASE_URL=http://localhost:8080 load-tests/transfer-load.js    # p95 latency at 100 requests/s
```

For the load tests, start the stack with `RATE_LIMIT_PER_SECOND=1000 RATE_LIMIT_BURST=2000` so the rate limit does not reject them.

## Deployment

Every push to `main` runs the tests, builds one image per service, deploys to a temporary `kind` Kubernetes cluster in GitHub Actions and smoke tests it through the gateway.

The AKS job in [.github/workflows/ci.yml](.github/workflows/ci.yml) pushes to Azure Container Registry and deploys [k8s/overlays/aks](k8s/overlays/aks). It runs when the repository variable `AKS_ENABLED` is `true`, and has not yet been run against a real Azure subscription.
