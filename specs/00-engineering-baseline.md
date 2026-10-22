# 00 Engineering baseline

Applies to every module. Service specs only state deviations.

## 1. Repository layout

One Git repository, one Maven multi-module build ([ADR-001](../architecture/decisions/ADR-001-microservices-maven-monorepo.md)).

```
tiny_payment_engine/
├── pom.xml                         # parent + aggregator: Spring Boot BOM, plugin management, Java release 25
├── mvnw, .mvn/                     # Maven wrapper (pinned Maven 3.9.x)
├── libs/
│   ├── payment-domain/             # value objects: Money, Iban, Bic, TxId, ReasonCode, BankDirectory, ...
│   ├── iso20022-model/             # XSDs, JAXB-generated classes, codec + XSD validation
│   ├── messaging/                  # outbox, relay, inbox, DLT handling, polling workers
│   ├── observability/              # telemetry setup, payment span attributes, trace context codec (26 §4)
│   └── contracts/                  # Kafka message records, internal REST DTOs, golden samples
├── services/
│   ├── payment-api/
│   ├── payment-orchestrator/
│   ├── ledger-service/
│   ├── vop-service/
│   ├── risk-service/
│   ├── csm-gateway/
│   └── notification-service/
├── simulators/
│   └── csm-simulator/
├── tools/                          # operational tools, never on the payment path (26 §8, §10, §12)
│   ├── slo-rules/                  # SLO files → Prometheus rules + SLO dashboards
│   ├── consumer-lag-exporter/
│   └── invariant-checker/
├── tests/
│   └── system-tests/               # end-to-end scenarios against the full docker-compose stack
├── pacts/
│   └── released/<version>/         # pact files of each release, verified by every build (20 §10.3)
├── deploy/
│   ├── docker-compose.yml          # PostgreSQL, Kafka, mock OAuth2 issuer, all services
│   ├── postgres/init.sql           # one database + one role per service
│   ├── config/bank-directory.yml   # shared routing directory (see 01 §1.4)
│   ├── slo/<service>.yml           # SLO definitions (25 §9.1)
│   └── observability/              # Alloy, backend values, Grafana, rules, Alertmanager, runbooks, probes (26)
├── architecture/
└── specs/
```

Maven coordinates: `groupId` `com.tpe`, `artifactId` = directory name, version `0.1.0-SNAPSHOT` for all modules
(released together). Java root package `com.tpe.<module>` with dashes dropped: `com.tpe.ledger`, `com.tpe.csmgateway`,
`com.tpe.paymentapi`, …

Dependency rules (enforced by the Maven Enforcer plugin and ArchUnit):

- `services/*` and `simulators/*` MAY depend on any `libs/*`. They MUST NOT depend on each other.
- `libs/payment-domain` depends on nothing but the JDK.
- `libs/contracts` depends only on `payment-domain` and Jackson annotations.
- `libs/messaging` depends on `contracts`, Spring Kafka, Spring JDBC. It contains no business logic.
- `libs/iso20022-model` depends only on `payment-domain` and Jakarta XML Binding.
- `libs/observability` depends only on `payment-domain`, Micrometer, Micrometer Tracing, OpenTelemetry and Spring
  Boot autoconfigure. It contains no business logic. `messaging` depends on it.
- `tools/*` MAY depend on `libs/*` and MUST NOT be called by any service.
- `csm-simulator` MUST NOT be packaged into any production image.

## 2. Technology stack

| Concern | Choice |
|---|---|
| Language | Java 25 (records, sealed types, pattern matching, virtual threads allowed) |
| Framework | Spring Boot 4.0.x (MVC, not WebFlux) |
| HTTP clients | Spring `RestClient` with JDK `HttpClient`; explicit connect and read timeouts on every client |
| Persistence | Spring Data JDBC + `JdbcClient` for hand-written SQL; no JPA / Hibernate |
| Migrations | Flyway |
| Database | PostgreSQL 17+, one database per service |
| Messaging | Apache Kafka 4.x (KRaft), Spring Kafka |
| JSON | Jackson; `FAIL_ON_UNKNOWN_PROPERTIES = false` for consumed messages and internal REST request bodies (a newer caller may add optional fields during a rolling deploy, [20 §10.3](20-testing-strategy.md#103-verifying-pacts)), `true` for customer-facing REST request bodies (payment-api, notification subscriptions) |
| XML | Jakarta XML Binding (JAXB) via `iso20022-model` |
| Security | Spring Security OAuth2 resource server (JWT) |
| Observability | Micrometer metrics (Prometheus scrape), Micrometer Tracing with OpenTelemetry (OTLP export), JSON structured logs; pipeline Grafana Alloy → Mimir, Loki, Tempo, Grafana ([ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md), [26](26-observability.md)) |
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka), WireMock, ArchUnit, Awaitility, Pact JVM (consumer `junit5`, provider `junit5spring`, Pact specification V4) |
| Performance tests | Gatling (Java DSL) in `tests/system-tests` |

Exact library versions come from the Spring Boot BOM; anything not in the BOM is pinned in the parent POM
`<properties>`. No version ranges, no `LATEST`.

## 3. Service internal structure

Each service follows the layering of its C4 component view (inbound adapters → application core → outbound adapters):

```
com.tpe.<svc>
├── api/              # inbound adapters: REST controllers, Kafka listeners, request/response mapping
├── application/      # application services (one per C4 "Application service" component), transactions live here
├── domain/           # entities, value objects, state machines, domain rules — no Spring, no I/O
└── infrastructure/   # repositories, REST clients, outbox wiring, schedulers, configuration
```

ArchUnit rules (shared test fixture in the parent):

- `domain` MUST NOT depend on `api`, `application`, `infrastructure`, Spring or Jackson.
- `api` MUST NOT depend on `infrastructure` directly.
- `@Transactional` only on `application` classes.
- No class outside `infrastructure` imports `org.springframework.kafka` or `org.springframework.jdbc`.

## 4. Identifiers

| Id | Format | Generated by |
|---|---|---|
| `paymentId` | UUID v7 (time-ordered) | payment-orchestrator, both directions |
| `commandId` | UUID v5, namespace `TPE_COMMAND_NS` (constant in `payment-domain`), name `"<clientId>\n<Idempotency-Key>"` | payment-api |
| `TxId` (outgoing) | `TPE` + 26-char Crockford ULID → 29 chars, `[A-Z0-9]` only | payment-orchestrator |
| `UETR` | UUID v4, lower case | payment-orchestrator (outgoing); taken from `pacs.008` (incoming) |
| `MsgId` (outgoing ISO messages) | `TPEM` + ULID → 30 chars | csm-gateway, one per message, including retries of the *same* message (retries reuse the MsgId) |
| `holdId` | = `paymentId` | ledger-service caller |
| pending credit id | = incoming `paymentId` ([ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md)) | ledger-service caller |
| `vopReference` | `VOP-` + ULID | vop-service |
| `eventId` | UUID v7 | outbox writer of the producer |
| account `iban` | Generated: country `DE`, check digits, our bank code (`tpe.bank.bank-code`), 10-digit account number from a DB sequence | ledger-service |

The identity of an interbank transaction is the pair (`debtorAgentBic`, `TxId`), never `TxId` alone: `TxId` is unique
only per Originator PSP ([ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md)).

All ids are opaque to consumers: never parse them for business meaning (the only exception is the bank code
inside an IBAN, used for routing).

## 5. Money

- Amounts are `long` **minor units** (euro cents) everywhere inside the system: Java (`Money` value object in
  `payment-domain`), DB (`BIGINT amount_minor` with `CHECK (amount_minor > 0)` where applicable) and Kafka messages
  (`amountMinor`).
- Currency is always present next to an amount (`currency`, ISO 4217, `EUR` only — A3). Code MUST reject any other
  value rather than assume EUR.
- At the edges only, amounts are decimal strings with exactly the precision given: customer REST API `"123.45"`,
  ISO 20022 XML `123.45`. Conversion lives in `Money.parse` / `Money.toDecimalString` and nowhere else.
- `double` / `float` for money is a build-breaking ArchUnit violation (rule: no `double` fields in classes named
  `*Amount*`, `*Money*`, `*Balance*`, plus code review).
- SCT Inst scheme maximum per transaction: 999 999 999.99 EUR. Smallest: 0.01 EUR.

## 6. Time

- All timestamps are `java.time.Instant`, UTC, serialised as ISO 8601 with millisecond precision
  (`2026-10-21T09:15:30.123Z`). DB columns are `timestamptz`.
- Every class that reads the current time takes an injected `java.time.Clock`. `Instant.now()` without a clock is
  an ArchUnit violation. Tests use a controllable `MutableClock` from `payment-domain` test fixtures.
- Business deadlines (`deadlineAt`, `sendBy`, `answerDeadline`) are absolute instants carried in messages, never
  durations. They are compared against the local clock of the deciding component.
- All hosts MUST run NTP; clock skew between services MUST stay below 100 ms. The default `sendBy` margin
  (10 s before the 20 s deadline) absorbs skew by a wide margin.

## 7. REST conventions

- Path prefix `/v1/...`; breaking changes go to `/v2`. Internal and external APIs follow the same rules.
- JSON bodies, `Content-Type: application/json`, except where an endpoint explicitly accepts or returns ISO 20022
  XML (`application/xml`).
- **Errors** use RFC 9457 Problem Details (`application/problem+json`) with two extensions:

  ```json
  {
    "type": "https://tpe.example/problems/idempotency-key-reused",
    "title": "Idempotency-Key reused with a different payload",
    "status": 422,
    "code": "IDEMPOTENCY_KEY_REUSED",
    "detail": "Key 'abc-123' was first used for a different request body.",
    "instance": "/v1/payments",
    "errors": [ { "field": "creditor.iban", "code": "INVALID_IBAN", "message": "checksum mismatch" } ]
  }
  ```

  `code` is a stable, upper-snake-case machine code listed in each service spec; clients switch on `code`, never
  on `title` or `detail`. `errors` is present only for validation failures (400).
- Status code usage: `400` malformed / schema invalid, `401` unauthenticated, `403` not allowed, `404` not found
  (also for "exists but not yours"), `409` conflict with current resource state, `422` well-formed but refused by a
  business rule, `429` over a rate limit (with `Retry-After`, §12.3 SEC-04), `503` dependency unavailable (with
  `Retry-After`).
- Every mutating internal call carries `Idempotency-Key` (see each API). The receiving service stores the result
  and replays it for the same key; same key with a different body → `409 IDEMPOTENCY_CONFLICT` (internal APIs) or
  `422 IDEMPOTENCY_KEY_REUSED` (customer API, UC-01 2b).
- Every service publishes its OpenAPI 3.1 document at `/v3/api-docs` (springdoc). The document is generated from
  code and checked into `src/main/resources/openapi/` by a build step; CI fails if it changed without being committed.
- Request and response logs MUST NOT contain names, IBANs or remittance text at INFO level (see §11).

## 8. Timeouts and retries for synchronous calls

- Every `RestClient` has connect timeout 200 ms and a per-call read timeout. Defaults per client are in the service
  specs; on the payment path the read timeout is `min(configured, remaining deadline − safety margin)`.
- Retries happen only for idempotent calls (all internal mutating calls are idempotent by key) and only on
  connect failure, read timeout, `502`, `503`, `504`. Never retry on `4xx`.
- No retry library magic: retries are explicit in the application service so they can respect the payment deadline.
- Circuit breakers are not used on the payment path in the MVP (fail-closed behaviour is explicit per call).

## 9. Database and migrations

- One PostgreSQL database and one login role per service (`ledger`, `payments`, `csm`, …); the role owns only its
  database. Local compose creates them in `deploy/postgres/init.sql`.
- Flyway scripts in `src/main/resources/db/migration`, versioned `V<n>__<description>.sql` with `n` increasing per
  service. Library-provided tables (outbox, inbox, polling locks) come from `classpath:db/migration/messaging` with
  versions `V0_<n>__…` so they never collide with service versions.
- **24/7 rule (SCT Inst has no maintenance window):** every migration must be compatible with the previous release
  still running. Use expand / contract: add nullable column → deploy code writing both → backfill → make
  required / drop old in a later release. No `ALTER TABLE … RENAME`, no column type change in place, no long locks
  (create indexes `CONCURRENTLY`, in their own migration with `-- flyway:executeInTransaction=false`).
- Isolation level `READ COMMITTED` (PostgreSQL default). Where a spec needs stronger guarantees it uses explicit
  row locks (`SELECT … FOR UPDATE`) or conditional updates (`UPDATE … WHERE status = 'X'`), stated in the spec.
- Optimistic locking: tables that say so carry `version BIGINT NOT NULL`; updates use `WHERE version = :expected`
  and treat 0 affected rows as a conflict.
- Money columns `BIGINT`, ids `UUID` or `VARCHAR(35)` (ISO identifiers), enums as `VARCHAR` with a `CHECK` constraint
  (not PostgreSQL enum types, which are hard to migrate).

## 10. Persistent timers and polling workers

payment-orchestrator, csm-gateway, notification-service and the outbox relay all need "do X at time T, exactly
once, on any instance". They all use the same pattern, implemented once as `PollingWorker` in `messaging`
([01 §3.5](01-shared-libraries.md#35-polling-worker)):

```sql
SELECT … FROM <timer table>
 WHERE status = 'SCHEDULED' AND due_at <= now()
 ORDER BY due_at
 LIMIT :batch
 FOR UPDATE SKIP LOCKED;
```

- The handler runs inside the same transaction as the select; it marks the row `FIRED` (or reschedules it) before
  commit. A crash rolls the transaction back and another instance picks the row up.
- Handlers MUST NOT make remote calls while holding the row lock. They record a decision / enqueue work and commit;
  remote calls happen afterwards (see orchestrator saga runner, [11 §6.3](11-payment-orchestrator.md#63-saga-runner)).
- Poll interval default 100 ms for payment-path timers, 1 s for everything else. Timers are allowed to fire late by
  up to one poll interval plus processing time; the specs size their deadlines with that in mind.

## 11. Logging, tracing, metrics

- Logs are JSON (Spring Boot structured logging, ECS format) to stdout.
- MDC keys, set by inbound adapters and propagated: `paymentId`, `txId`, `uetr`, `commandId`, `eventId`, `traceId`,
  `spanId`.
- **Personal data:** names, IBANs, dates of birth, remittance information are personal data. They MUST NOT be logged
  at INFO or above. At DEBUG, IBANs are masked (`DE89****3000`). Names are never logged.
- Tracing: W3C `traceparent` propagated over HTTP and as a Kafka header. One trace covers a payment's automated
  lifecycle, parent / child across `csm.*` topics; notifications, account events, DLT handling and human actions
  start linked traces; no trace headers to or from external parties
  ([ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md), [26 §5](26-observability.md#5-payment-trace-model)).
- Every service exposes `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`.
  Readiness includes DB and (for Kafka consumers) Kafka connectivity.
- Common metrics provided by `messaging`: `tpe.outbox.pending` (gauge), `tpe.outbox.publish.latency` (timer: insert →
  Kafka ack), `tpe.inbox.duplicates` (counter), `tpe.dlt.messages` (counter, tag `topic`), `tpe.worker.lag` (gauge,
  oldest due timer age, tag `worker`).
- Service-specific metrics are listed in each spec. Metric names are `tpe.<area>.<name>`, tags low-cardinality only
  (never `paymentId`).
- Telemetry never blocks a business operation: span export drops on overload, logs go to stdout, metrics are pulled.
  Pipeline, storage, dashboards and alert delivery: [26](26-observability.md).

## 12. Security

Authentication and authorisation are decided in [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md).
§12.1 and §12.2 apply from M0; §12.3 lists what must also hold before production, with the decisions in
[ADR-015 … ADR-018](../architecture/decisions/) and [40](40-open-questions.md) (Q-30 … Q-36).

### 12.1 Authentication and authorisation

- **Customer API (payment-api) and subscription API (notification-service):** OAuth2 resource server, JWT. Required
  claims: `sub`, `client_id` (API client), `scope`, `accounts` (array of IBANs the subject may debit / read).
  Scopes: `payments:write`, `payments:read`, `vop:verify`, `notifications:manage`. Who authenticates the customer and
  issues `accounts`: SEC-05.
- **Back-office and compliance APIs:** same issuer, claim `roles` contains `BACKOFFICE_OPERATOR` or
  `COMPLIANCE_OFFICER`. `sub` is recorded as the actor in audit records.
- **Internal service APIs:** M0 – M4 rely on network isolation only: they MUST NOT be reachable from outside the
  internal network (compose network / Kubernetes NetworkPolicy), and every internal controller is annotated
  `@InternalApi`. From M5, internal calls carry OAuth2 client-credentials tokens with the per-capability scopes of
  [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md), switched on by
  `tpe.security.internal-auth.enabled` and mandatory in `prod`.
- **Local:** `deploy/docker-compose.yml` runs `ghcr.io/navikt/mock-oauth2-server` as issuer; system tests mint tokens
  from it.

### 12.2 Data handling

- Personal data stays out of logs and telemetry (§11, [26 §4.4](26-observability.md#44-personal-data-guard-in-code)).
- XML parsers reject DOCTYPE and external entities ([01 §2](01-shared-libraries.md#2-iso20022-model)).
- Webhooks: `https` only, SSRF guard, HMAC-signed, secrets encrypted ([16 §3](16-notification-service.md#3-rest-api)).
- Secrets (DB passwords, webhook-secret encryption key, VoP pepper, OAuth2 client secrets, keystores) are never
  committed and never baked into images. Until SEC-03 is in place (from M5) they come from environment variables; in `prod` they never do
  ([ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md)).

### 12.3 Production requirements

Each requirement must hold in `prod` before production use. The requirement says what must be true; the linked ADR and
question say how, and who still signs it off.

| # | Requirement | Decision |
|---|---|---|
| SEC-01 | **Kafka.** Brokers accept only authenticated clients, one principal per service, over TLS. ACLs follow the producer / consumer columns of [02 §2](02-messaging-contracts.md#2-topics): only payment-orchestrator writes `csm.outbound.commands` and `payments.events`, only csm-gateway writes `csm.inbound.events`, only ledger-service writes `accounts.events`; each consumer reads only its topics with its own group and writes only the `.dlt` of the topics it consumes. The `systemtest` profile runs with ACLs on, so a missing ACL fails the tests. | [ADR-015](../architecture/decisions/ADR-015-kafka-authentication-and-acls.md), [Q-30](40-open-questions.md#q-30-kafka-authentication-and-authorisation) |
| SEC-02 | **Encryption in transit inside the cluster.** REST between services, PostgreSQL connections (`sslmode=verify-full`), Kafka (SEC-01) and telemetry export use TLS. Identity stays with the JWT (ADR-012); TLS adds confidentiality and server authentication. | [ADR-016](../architecture/decisions/ADR-016-tls-inside-the-cluster.md), [Q-31](40-open-questions.md#q-31-tls-inside-the-cluster) |
| SEC-03 | **Secrets.** Every secret comes from a secret store at runtime, has an owner and a rotation procedure that needs no downtime: the webhook encryption key (new writes use the newest `secret_key_id`, a job re-encrypts, the old key is retired), the VoP pepper (versioned, old versions kept until the `responder_audit` rows hashed with them expire), OAuth2 client secrets, mTLS keystores (alert 30 d before certificate expiry). | [ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md), [Q-32](40-open-questions.md#q-32-secret-store-and-rotation) |
| SEC-04 | **Abuse limits.** payment-api limits requests per `client_id` and per `sub`, with tighter limits on `POST /v1/payee-verifications`, so VoP cannot be used to harvest account holder names. The VoP scheme responder limits requests per requesting PSP, and in `prod` `requestingPspBic` must match the identity in the mTLS client certificate. Requests over a limit get `429 RATE_LIMITED` with `Retry-After`. | [ADR-018](../architecture/decisions/ADR-018-rate-limiting.md), [Q-33](40-open-questions.md#q-33-rate-limits) |
| SEC-05 | **Customer authentication.** The engine does not authenticate customers. The channel and the identity provider perform strong customer authentication (PSD2), including dynamic linking of amount and payee for payments, before issuing a token with `payments:write`. The `accounts` claim comes from the bank's entitlement data; revoked access takes effect within one access-token lifetime. | [Q-34](40-open-questions.md#q-34-customer-authentication-and-the-accounts-claim) |
| SEC-06 | **Audit integrity.** Audit records (`audit_log` in ledger-service, `payment_transitions` in payment-orchestrator, `responder_audit` in vop-service) are append-only: the service's runtime DB role has `INSERT` and `SELECT` only on them, granted by migration; deletion after the retention period runs under a separate retention role. Staff reads of personal data (account holder data, ledger entries, gateway journal XML, screening cases, webhook deliveries) are audited with actor, endpoint, subject id and time. Authentication and authorisation failures are logged as structured security events (ECS `event.category` = `authentication` / `authorization`) for the bank's security monitoring. | [Q-35](40-open-questions.md#q-35-audit-retention-and-security-events) |
| SEC-07 | **Encryption at rest.** PostgreSQL storage, backups (PITR, [25 §8](25-service-levels.md#8-durability-and-recovery)), Kafka log directories and telemetry object storage are encrypted with keys managed outside the cluster. | [ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md), [Q-36](40-open-questions.md#q-36-encryption-at-rest-and-key-management) |
| SEC-08 | **Supply chain.** CI scans dependencies and container images for known vulnerabilities and fails on CVSS ≥ 7 unless a suppression with reason and expiry date is committed. Every release has a CycloneDX SBOM. Images use base images pinned by digest and run as a non-root user with a read-only root file system. | — (§14) |
| SEC-09 | **Threat model.** A STRIDE pass over the [container view](../architecture/c4/02-containers.puml) and its trust boundaries (channels, staff, CSM, counterparty PSPs, sanctions provider, Kafka, databases), kept in `architecture/` and reviewed whenever a container or trust boundary changes. Findings become requirements here or questions in [40](40-open-questions.md). | — ([30 M5](30-delivery-plan.md#m5--hardening)) |

## 13. Local development

```bash
./mvnw -q verify                      # build + unit + integration tests (Testcontainers)
docker compose -f deploy/docker-compose.yml up -d postgres kafka oauth2   # infra only
./mvnw -pl services/ledger-service spring-boot:run -Dspring-boot.run.profiles=local
docker compose -f deploy/docker-compose.yml up -d --build    # whole stack incl. csm-simulator
```

| Service | Port | Database |
|---|---|---|
| payment-api | 8080 | `api` |
| payment-orchestrator | 8081 | `payments` |
| ledger-service | 8082 | `ledger` |
| vop-service | 8083 | `vop` |
| risk-service | 8084 | `risk` |
| csm-gateway | 8085 | `csm` |
| notification-service | 8086 | `notification` |
| csm-simulator | 8090 | — (in memory) |
| PostgreSQL | 5432 | |
| Kafka | 9092 | |
| mock OAuth2 issuer | 8099 | |

Profiles: `local` (compose hosts, debug logging off, funding endpoint enabled), `test` (Testcontainers),
`systemtest` (compose stack with shortened deadlines and retry schedules, values in
[20 §2.1](20-testing-strategy.md#21-systemtest-profile)), `prod` (funding endpoint disabled, simulator URLs
forbidden by a startup check).

Kafka topics and ACLs are declared in `deploy/kafka/topics.yml` ([ADR-015](../architecture/decisions/ADR-015-kafka-authentication-and-acls.md))
and created by a `kafka-init` compose job (and by `NewTopic` beans in tests), not by auto-creation
(`auto.create.topics.enable=false`). Topic settings: [02 §2](02-messaging-contracts.md#2-topics).

## 14. Continuous integration

Pipeline on every pull request and on `main`:

1. `./mvnw -B verify` — compile, unit tests (including Pact consumer tests, which write the pact files), Testcontainers
   integration tests, ArchUnit, contract golden-sample tests, OpenAPI drift check, Enforcer rules.
2. `./mvnw -B -Ppact-verify verify` — every provider verifies this commit's pacts and those of the last release in
   `pacts/released/` ([20 §10.3](20-testing-strategy.md#103-verifying-pacts)).
3. `plantuml -checkonly architecture/**/*.puml`.
4. Build container images (Jib or Spring Boot buildpacks), tag with commit SHA; scan the dependency tree and the images,
   and on release write the SBOM (§12.3 SEC-08).
5. `tests/system-tests`: start the compose stack, run the scenario catalogue tagged `@smoke` on PRs, everything on `main`.
6. Nightly: full scenario catalogue, Gatling load profile, failure-injection scenarios ([20 §5](20-testing-strategy.md#5-failure-injection-scenarios)),
   forward pact check (the last release's providers against this commit's pacts).
7. Release: the forward pact check must be green; the release job copies the pact files to `pacts/released/<version>/`.

Branching: trunk-based, short-lived feature branches, squash merge. Conventional Commits for messages.
