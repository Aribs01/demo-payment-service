# Transfer Service

A reliable, asynchronous bank transfer service built with Java 21, Spring Boot 4.1.1, PostgreSQL, and RabbitMQ.

The application integrates with simulated Apache Fineract and payment provider APIs to demonstrate financial idempotency, concurrency control, failure recovery, refund handling, and reconciliation.

## 1. Overview

The service allows authenticated API clients to initiate transfers from authorized source accounts and retrieve transfer statuses.

Transfers are accepted synchronously and processed asynchronously through RabbitMQ.

The implementation is designed to handle:

- Duplicate transfer requests and concurrent submissions.
- Insufficient available account balances.
- External financial operations that commit before an HTTP timeout.
- Worker crashes and message redelivery.
- RabbitMQ outages.
- Delayed, duplicate, or conflicting provider callbacks.
- Payment failures requiring refunds.
- Recovery of transfers with unresolved outcomes.

**Scope:** NGN transfers only. Fees and taxes are excluded.

## 2. Technology Stack

| Technology | Purpose |
|---|---|
| Java 21 | Application language |
| Spring Boot 4.1.1 | Application framework |
| Spring Security | API-key authentication |
| Spring Data JPA / JDBC | Database access |
| PostgreSQL | Persistent state and financial records |
| Flyway | Database migrations |
| RabbitMQ | Asynchronous transfer processing |
| Docker Compose | Local infrastructure |
| Maven | Build and dependency management |
| JUnit / Spring Boot Test | Automated testing |

## 3. Architecture

```text
                   API Client
                       |
                       | X-API-Key
                       v
              Transfer REST API
                       |
              Validate and authorize
                       |
              PostgreSQL transaction
                 /     |      \
                v      v       v
           Transfer   Audit   Outbox
                                |
                                v
                         Outbox Publisher
                                |
                                v
                             RabbitMQ
                                |
                                v
                         Transfer Worker
                                |
                       HTTP integration
                          /          \
                         v            v
                  Mock Fineract   Mock Provider
                         |            |
                         v            v
                  Debit / Refund   Payment
                                      |
                                      v
                               Provider Callback
                                      |
                                      v
                              Transfer Service
```

The mock integrations run within the same Spring Boot application for ease of deployment, but the transfer processing layer communicates with them through HTTP.

Each mock persists its own operation outcomes, allowing the application to reproduce realistic external-system failure scenarios.

## 4. Prerequisites

Install:

- Java 21
- Docker and Docker Compose
- Maven (or use the included Maven wrapper)
- Git

Verify:

```bash
java -version
docker --version
docker compose version
```

## 5. Running the Application

### Step 1: Clone the repository

```bash
git clone <REPOSITORY_URL>
cd <PROJECT_DIRECTORY>
```

### Step 2: Start PostgreSQL and RabbitMQ

```bash
docker compose up -d
```

The development environment uses:

| Service | Port |
|---|---|
| Transfer API | 3333 |
| PostgreSQL | 5433 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ Management UI | 15672 |

RabbitMQ Management UI:

http://localhost:15672

Development RabbitMQ credentials:

```text
Username: transfers
Password: transfers
```

### Step 3: Start Spring Boot

```bash
./mvnw spring-boot:run
```

Flyway automatically applies the database migrations during application startup.

### Step 4: Verify application health

```bash
curl http://localhost:3333/actuator/health
```

Expected:

```json
{
  "status": "UP"
}
```

### Step 5: Run tests

```bash
./mvnw clean test
```

For a complete build:

```bash
./mvnw clean verify
```

**Note:** The development environment uses local credentials and seeded mock data. These credentials are not suitable for production.

## 6. Authentication

Transfer endpoints require an API key in the `X-API-Key` request header.

```http
X-API-Key: demo-secret-key
```

The application hashes the supplied key and resolves the authenticated client from PostgreSQL.

Client identity is never accepted from the request body.

Each client can initiate transfers only from source accounts explicitly assigned to it.

Transfer status lookups are scoped to the authenticated client.

## 7. API Endpoints

### Initiate transfer

`POST /api/v2/transfers`

```bash
curl -X POST http://localhost:3333/api/v2/transfers \
  -H "Content-Type: application/json" \
  -H "X-API-Key: demo-secret-key" \
  -d '{
    "externalReference": "merchant-000001",
    "sourceAccount": "1001610327",
    "destinationAccount": "0123456789",
    "destinationBankCode": "000004",
    "amount": "25000.00",
    "currency": "NGN",
    "narration": "Invoice payment"
  }'
```

A successfully accepted request returns HTTP `202 Accepted` and a transfer response containing its reference and current status.

Acceptance means the transfer has been durably recorded. It does not mean the payment has completed.

### Retrieve transfer status

`GET /api/v2/transfers/{reference}`

```bash
curl \
  -H "X-API-Key: demo-secret-key" \
  http://localhost:3333/api/v2/transfers/merchant-000001
```

### Provider callback

`POST /api/v2/provider/events`

The payment provider uses this endpoint to report payment outcomes.

Callbacks are correlated using stable payment operation references. Duplicate and conflicting events are handled without blindly overwriting previously confirmed financial outcomes.

**Authentication and callback payload:** Document the exact callback headers and JSON structure implemented in the application here.

## 8. Transfer Lifecycle

The transfer progresses through the following logical states:

```text
ACCEPTED
    |
    v
DEBIT_PENDING
    |
    +---- Debit failed ----------> FAILED
    |
    v
DEBIT_CONFIRMED
    |
    v
PAYMENT_PENDING
    |
    +---- Provider success ------> COMPLETED
    |
    +---- Provider failure ------> REFUND_PENDING
    |                                   |
    |                                   v
    |                                REFUNDED
    |
    +---- Outcome unknown -------> PAYMENT_PENDING
                                        |
                                        v
                                 Recovery / Lookup
```

Unresolved operations remain pending until authoritative evidence is available.

Conflicting or late financial evidence is retained and flagged for reconciliation rather than silently rewriting financial history.

**Important:** The diagram describes the intended lifecycle. Verify that every transition matches the implemented state machine, including how reconciliation is represented.

## 9. Idempotency and Concurrency

### Transfer initiation

Each transfer is uniquely identified by:

```text
(client_id, external_reference)
```

The database enforces this using a unique constraint.

Behavior:

| Request | Result |
|---|---|
| New reference | New transfer accepted |
| Same reference and same payload | Existing transfer returned |
| Same reference and different payload | HTTP 409 Conflict |
| Same reference from another client | Independent transfer |

A deterministic request fingerprint is stored to identify conflicting payloads.

Concurrent inserts are resolved through PostgreSQL uniqueness constraints rather than in-memory locks.

### Financial operations

Each debit, payment, and refund uses a stable operation reference.

External mocks persist their operation results and return the original result when an identical operation is retried.

This prevents retries from creating additional financial effects.

### Balance concurrency

Mock Fineract uses conditional PostgreSQL balance updates to ensure available funds cannot be overspent.

The available balance is:

```text
available_balance = balance - held_amount
```

Debit operations succeed only when sufficient available funds exist.

### Refund safety

Refunds are associated with their original successful debit.

Database uniqueness constraints prevent multiple successful refunds for the same original debit.

## 10. Transactional Outbox and RabbitMQ

Transfer acceptance and outbox event creation occur in the same PostgreSQL transaction.

This ensures:

- A committed transfer has a durable event awaiting publication.
- A failed database transaction does not leave an orphaned event.
- RabbitMQ downtime does not cause an already accepted transfer to disappear.

The outbox publisher asynchronously delivers pending events to RabbitMQ.

Consumers must tolerate duplicate delivery because message delivery is not assumed to be exactly once.

Financial correctness is achieved through persistent operation references, database constraints, and idempotent external APIs.

**Verify and document:** Whether the implementation uses publisher confirms, how concurrent publishers claim events, and when messages are acknowledged.

## 11. Failure Recovery

### Fineract debit commits but HTTP response times out

1. The worker submits a debit using a stable operation reference.
2. Mock Fineract commits the debit.
3. The HTTP response is delayed or lost.
4. The worker cannot safely assume that the debit failed.
5. Recovery queries Fineract using the original operation reference.
6. The confirmed debit is reused without performing another debit.

### Worker crashes after debit

The external debit may already have succeeded even when the local transfer state has not been updated.

A replacement worker recovers the transfer using the persisted operation reference and external operation lookup.

### Provider submission times out

A provider timeout is treated as an unknown outcome, not as payment failure.

The service uses provider lookup and subsequent authoritative events to determine whether processing should continue.

An unresolved provider outcome must not trigger an automatic refund.

### Provider confirms failure

A confirmed failed payment becomes eligible for refund processing.

The refund references the original debit and is idempotent.

Concurrent recovery attempts must not create multiple refunds.

### Conflicting provider callbacks

Duplicate events must not cause duplicate financial effects.

If a success event arrives after a completed refund, the system preserves the confirmed refund and flags the transfer for reconciliation.

Such contradictions require investigation rather than an automatic second financial operation.

## 12. Fault Injection and Testing

The mocks provide controls for reproducing failures.

### Simulate a committed debit followed by an HTTP timeout

```bash
curl --max-time 2 -X POST \
  http://localhost:3333/mock/fineract/debits \
  -H "Content-Type: application/json" \
  -H "X-Mock-Admin-Key: local-mock-admin-key" \
  -H "X-Mock-Delay-After-Commit: true" \
  -d '{
    "operationReference": "timeout-debit-demo",
    "accountNumber": "1001610327",
    "amount": 1000.00
  }'
```

The HTTP request times out, but the debit operation should already be committed.

Verify:

```bash
curl \
  -H "X-Mock-Admin-Key: local-mock-admin-key" \
  http://localhost:3333/mock/fineract/operations/timeout-debit-demo
```

### Simulate a provider submission timeout

```bash
curl --max-time 2 -X POST \
  http://localhost:3333/mock/provider/payments \
  -H "Content-Type: application/json" \
  -H "X-Mock-Admin-Key: local-mock-admin-key" \
  -H "X-Mock-Delay-After-Commit: true" \
  -d '{
    "operationReference": "timeout-payment-demo",
    "destinationAccount": "0123456789",
    "destinationBankCode": "000004",
    "amount": 1000.00,
    "currency": "NGN"
  }'
```

Verify that the payment was accepted:

```bash
curl \
  -H "X-Mock-Admin-Key: local-mock-admin-key" \
  http://localhost:3333/mock/provider/payments/timeout-payment-demo
```

### Simulate a RabbitMQ outage

Stop RabbitMQ:

```bash
docker compose stop rabbitmq
```

Initiate a transfer and confirm that it is durably accepted with an unpublished outbox event.

Restart RabbitMQ:

```bash
docker compose start rabbitmq
```

Verify that the pending event is eventually published and the transfer resumes processing.

### Inspect committed financial effects

```bash
curl \
  -H "X-Mock-Admin-Key: local-mock-admin-key" \
  http://localhost:3333/mock/fineract/stats
```

```bash
curl \
  -H "X-Mock-Admin-Key: local-mock-admin-key" \
  http://localhost:3333/mock/provider/stats
```

These endpoints count committed operations rather than HTTP attempts.

**Additional fault scenarios:** Include the actual trigger commands for worker crashes, duplicate callbacks, delayed callbacks, conflicting callbacks, and recovery jobs if supported by the implementation.

## 13. Automated Test Coverage

The assessment targets the following scenarios:

| # | Scenario | Expected invariant |
|---|---|---|
| 1 | 20 concurrent identical requests | One transfer and one debit |
| 2 | Concurrent transfers exceed available funds | No overspending |
| 3 | Debit commits before HTTP timeout | No second debit |
| 4 | Worker crashes after debit | Replacement recovers safely |
| 5 | RabbitMQ unavailable after acceptance | Transfer eventually resumes |
| 6 | Callback arrives before provider response persistence | Event is not lost |
| 7 | Duplicate callbacks and message redelivery | No duplicate financial effects |
| 8 | Provider timeout with unresolved outcome | No premature refund |
| 9 | Confirmed failure with concurrent recovery | Exactly one refund |
| 10 | Success callback after completed refund | Both facts preserved; reconciliation flagged |

Only describe a scenario as **automated and passing** if it has a corresponding test in the repository.

Tests should validate database state and committed mock operation counts, not just HTTP response codes.

## 14. Database Design

The main tables are:

| Table | Responsibility |
|---|---|
| `clients` | Authenticated API consumers |
| `client_accounts` | Authorized source accounts |
| `transfers` | Transfer requests and current state |
| `financial_operations` | Durable debit, payment and refund records |
| `transfer_audit` | Transfer event history |
| `outbox_events` | Pending RabbitMQ publications |
| `provider_events` | Provider callback evidence |
| `mock_fineract_accounts` | Mock account balances |
| `mock_fineract_operations` | Mock debit/refund outcomes |
| `mock_provider_payments` | Mock payment outcomes |

Database migrations are maintained in:

```text
src/main/resources/db/migration/
```

Schema changes are managed through Flyway, with Hibernate configured for schema validation.

## 15. Security Assumptions

- API clients authenticate through an API key.
- API keys are stored as hashes, not plaintext.
- Client identity comes from authentication, never from transfer request parameters.
- Source account ownership is checked before accepting transfers.
- Transfer lookups are scoped to the authenticated client.
- Mock administrative endpoints are intended only for local development.
- Production deployment requires HTTPS, secure secret management, restricted mock access, and appropriate rate limiting.
- Provider callbacks require an authenticated or cryptographically verified source in production.

## 16. Guarantees and Limitations

### Guarantees provided by the design

Under the documented database and integration assumptions, the service aims to provide:

- Durable transfer acceptance.
- Client-scoped initiation idempotency.
- Safe concurrent account debits.
- At-least-once asynchronous processing with idempotent financial effects.
- Recovery of externally committed operations through stable references and lookup.
- Protection against duplicate refunds.
- Preservation of contradictory financial evidence for reconciliation.

### External integration assumptions

These guarantees rely on Fineract and the payment provider supporting:

1. Stable idempotency references.
2. Durable storage of financial operation outcomes.
3. Authoritative operation lookup.
4. Consistent handling of repeated requests.

### If an external provider is not idempotent

Exactly-once financial effects cannot be guaranteed merely by retrying HTTP requests.

If an operation commits but its response is lost, blindly retrying could create a duplicate financial effect.

Without reliable idempotency or authoritative lookup, the service must preserve the uncertain state and require reconciliation rather than automatically retrying or refunding.

### Known limitations

- The implementation uses mock integrations rather than a live banking core or payment provider.
- Only NGN is supported.
- Fees, taxes and foreign exchange are outside scope.
- The development API key and mock administrative credentials are for local use only.
- Operational monitoring, alerting and deployment hardening would need further work before production use.

## 17. Design Trade-offs

**Transactional outbox over direct RabbitMQ publication:** Prevents accepted transfers from being lost when message publication fails.

**Database constraints over in-memory synchronization:** Maintains correctness across concurrent requests and multiple application instances.

**Stable external references over blind retries:** Allows recovery after uncertain HTTP outcomes.

**Separate financial operations and transfer state:** Preserves independent debit, payment and refund outcomes.

**Append-only audit history:** Supports investigations and reconciliation without relying exclusively on application logs.

**HTTP-based mocks:** Reproduces realistic external integration boundaries while keeping the assessment self-contained.

## 18. Submission

The repository contains the application source code, database migrations, mock integrations, test suite, Docker Compose configuration and this README.

The application can be started locally using:

```bash
docker compose up -d
./mvnw spring-boot:run
```

Run verification with:

```bash
./mvnw clean verify
```
