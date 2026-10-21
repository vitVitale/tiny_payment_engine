# 20 Testing strategy and scenario catalogue

Money correctness and deadline behaviour are the two things that must never regress. The test pyramid is built
around them.

## 1. Test levels

| Level | Where | Tooling | What it proves |
|---|---|---|---|
| Unit | each module, `src/test` | JUnit 5, AssertJ, `MutableClock` | domain rules: state machine transitions, posting engine, matchers, value objects, reason mapping |
| Property-based | `payment-domain`, ledger domain, `iso20022-model` | jqwik | invariants: Σ DR = Σ CR; available ≥ 0; every builder output is XSD-valid; IBAN round trips |
| Component (service) | each service, `src/integrationTest` (Failsafe) | Spring Boot test, Testcontainers PostgreSQL + Kafka, WireMock for REST neighbours | the service's acceptance criteria (`<SVC>-AC-nn`) in isolation |
| Contract | `contracts` + each consumer | golden samples, round-trip tests | message compatibility across versions |
| REST contract | provider: OpenAPI drift check; consumer: WireMock stubs generated from the provider's checked-in OpenAPI | springdoc, openapi-diff | internal APIs do not break silently |
| Architecture | each service | ArchUnit | layering, no `Instant.now()`, no `double` money |
| System (end-to-end) | `tests/system-tests` | JUnit 5 + Testcontainers Compose module, simulator control API, mock OAuth2 tokens | scenario catalogue §4 |
| Failure injection | `tests/system-tests` (`@Chaos`, nightly) | Toxiproxy between services / Kafka / DB, container pause / kill | §5 |
| Performance | `tests/system-tests/perf` (nightly) | Gatling | §6 |

## 2. Rules for writing tests

- Tests never sleep for business time. Services under component test get a `MutableClock` bean; system tests
  use short configured deadlines (profile `systemtest`: `send-by-offset=3s`, `deadline-offset=6s`,
  `hold-give-up-margin=1s`, `answer-deadline-offset=2s`, `verdict-give-up-margin=500ms`,
  `settlement-overdue=5s`, `investigation.backoff=500ms,1s`, `investigation.budget=10s`; simulator
  `timeout-deadline=6s`, `answer-timeout=3s`) and Awaitility with explicit upper bounds.
- One test per acceptance criterion at minimum, named after it (`ORC_AC_09_deadlineInSentToCsmStartsInvestigation`).
- Every system test ends with **global invariant checks** (§3) — failures there are always release blockers.
- Test data: unique IBANs / customer ids per test (`TestData.newCustomer()`), no shared fixtures that tests mutate.

## 3. Global invariants (asserted after every system test and continuously in the nightly soak)

| # | Invariant | How it is checked |
|---|---|---|
| INV-1 | Every journal entry balances | SQL on ledger DB |
| INV-2 | No customer account has `available < 0` or `booked < 0` | SQL |
| INV-3 | Every final outgoing payment has no `ACTIVE` hold; `SETTLED` ⇔ hold `SETTLED`; `REJECTED` ⇔ hold `RELEASED` or never created | join orchestrator DB ↔ ledger DB via admin APIs |
| INV-4 | Every final incoming payment has no `ACTIVE` pending credit; `SETTLED` ⇔ `CONFIRMED` | same |
| INV-5 | No `pacs.008` was transmitted after its `sendBy` | simulator received-log vs gateway `outgoing_transfers` |
| INV-6 | Exactly one answer `pacs.002` per incoming `TxId`, and the orchestrator state agrees with it | simulator log vs orchestrator |
| INV-7 | Exactly one `PaymentSettled` / `PaymentRejected` per final payment | test consumer on `payments.events` |
| INV-8 | Σ derived CSM settlement account balance = Σ settled incoming − Σ settled outgoing (per test window) | ledger + orchestrator |
| INV-9 | No DLT message, no unexpected exception case | Kafka DLT topics, case API |

## 4. End-to-end scenario catalogue

Each scenario lists the setup through the simulator control API and the expected outcome. `@smoke` scenarios run on
every PR.

### Outgoing

| Id | Scenario | Setup | Expected |
|---|---|---|---|
| E2E-O-01 `@smoke` | Happy path to another PSP | default `ACCEPT` | `202` → `ACSC`; payer debited; CSM settlement credited; webhook to payer |
| E2E-O-02 `@smoke` | On-us | payee is our customer | `ACSC`; single entry Dr payer / Cr payee; webhooks to both; no `pacs.008` |
| E2E-O-03 | Insufficient funds | balance < amount | `RJCT AM04`; no hold left |
| E2E-O-04 | Payer blocked | block payer (`OPERATOR`) | `RJCT AC06` |
| E2E-O-05 | Fraud rule | amount > 50 000 | `RJCT FR01`; no ledger call |
| E2E-O-06 | Non-EU sanctions creditor | UN list contains creditor name | `RJCT RR04` |
| E2E-O-07 | Beneficiary rejects | `REJECT:AC04` | `RJCT AC04`; hold released |
| E2E-O-08 | Beneficiary timeout | `TIMEOUT` | `RJCT AB05` via CSM `pacs.002`, or via investigation — either way released, never settled |
| E2E-O-09 | No `pacs.002`, settled | `SILENT` | `INVESTIGATING` → `pacs.028` → `ACSC`; payer debited |
| E2E-O-10 | No `pacs.002`, unknown | `LOST` | `INVESTIGATING` → `UNKNOWN_TX` after deadline → `RJCT AB05`; released |
| E2E-O-11 | Investigation unanswered | `SILENT` + `INVESTIGATION_SILENT` | retries, then `UNRESOLVED_INVESTIGATION` case; hold stays; status `PDNG`; operator resolves → final |
| E2E-O-12 | Gateway down past `sendBy` | stop csm-gateway before submit, start after `sendBy` | `RJCT AB03`; simulator received nothing |
| E2E-O-13 | CSM unreachable past `sendBy` | simulator outage `DOWN` during send | no `pacs.008` after `sendBy`; outcome resolved by investigation; no invariant violation |
| E2E-O-14 | Late contradicting `pacs.002` | `REJECT` then inject `ACSC` via raw endpoint | `RJCT`; `CONTRADICTING_CSM_OUTCOME` case; no money movement |
| E2E-O-15 | On-us payee blocked | block payee | `RJCT AC06`; hold released |
| E2E-O-16 | Idempotent retry | submit twice with same key; once with lost response (Toxiproxy) | one payment |
| E2E-O-17 | VoP required | no / expired reference | `422 VOP_REQUIRED`; no payment |

### Incoming

| Id | Scenario | Setup | Expected |
|---|---|---|---|
| E2E-I-01 `@smoke` | Happy path | incoming payment to open account | `ACCP` then `ACSC`; payee credited; webhook to payee |
| E2E-I-02 | Unknown account | creditor IBAN not ours | `RJCT AC01`; no pending credit |
| E2E-I-03 | Blocked account | block payee | `RJCT AC06` |
| E2E-I-04 | Closed account | close payee | `RJCT AC04` |
| E2E-I-05 | Risk deny | mule rule triggers | `RJCT FR01` |
| E2E-I-06 | Orchestrator slow | Toxiproxy delay orchestrator ↔ Kafka > answer deadline | gateway sends `RJCT AB05` itself; late verdict ignored; pending credit cancelled |
| E2E-I-07 | CSM rejects after `ACCP` | `REJECT_AFTER_ACCP` | `REJECTED`; pending credit cancelled; payee not credited, not notified |
| E2E-I-08 | No settlement confirmation | `NO_CONFIRMATION` | `INVESTIGATING` → `pacs.028` → resolved |
| E2E-I-09 | Invalid XML | raw invalid `pacs.008` | `RJCT FF01`; no payment created |
| E2E-I-10 | Duplicate `pacs.008` | send same twice | one payment, one credit; answer re-sent for the late duplicate |

### VoP, sanctions, accounts, notifications

| Id | Scenario | Expected |
|---|---|---|
| E2E-V-01 | VoP against counterparty: match / close match / no match / unknown | results as seeded; `CLOSE_MATCH` returns actual name |
| E2E-V-02 | Counterparty VoP slow (`VOP_SLOW`) | `NOT_POSSIBLE` within 5.2 s; payment still possible |
| E2E-V-03 | Counterparty asks our responder | correct result; audit row hashed |
| E2E-S-01 | New EU list with a customer | provisional block within minutes; outgoing and incoming payments `AC06`; confirm → `SANCTIONS`; dismiss → unblocked and not re-raised |
| E2E-S-02 | Provider outage | previous list kept; alert metric |
| E2E-A-01 | Open, fund, block, unblock, close | events reach vop / risk projections; close refused while not empty |
| E2E-N-01 | Webhook receiver down for 2 min | delivered after recovery; payments unaffected |

## 5. Failure-injection scenarios

Run nightly with a steady load of 20 payments/s (both directions) and assert the global invariants after the system
is quiesced:

| Id | Injection | Specifically checks |
|---|---|---|
| CH-01 | Kill a random payment-orchestrator instance every 30 s | saga resume (ADR-003) |
| CH-02 | Kill csm-gateway instance during sends | INV-5, `GAVE_UP` with 0 attempts → `NOT_SENT` |
| CH-03 | Pause outbox relay (orchestrator) for 15 s | ADR-004 send-by race: no `pacs.008` after `sendBy` |
| CH-04 | Delay `csm.outbound.commands` consumption past the answer deadline | ADR-004 answer race: INV-6 |
| CH-05 | Ledger latency 2 s / ledger down 30 s | holds give up correctly, settlements retried, no stuck holds after recovery |
| CH-06 | PostgreSQL failover / restart of one service DB | consumers block and resume, no DLT |
| CH-07 | Kafka broker restart | no message loss (INV-7 counts) |
| CH-08 | Clock skew +200 ms on gateway | margins hold; no invariant broken |

## 6. Performance

Target load ([Q-13](40-open-questions.md#q-13-target-load)): 100 payments/s sustained, 300/s peak for 5 min, 60 / 40
outgoing / incoming mix, 10 % on-us.

| Metric | Target |
|---|---|
| `POST /v1/payments` p99 | ≤ 300 ms |
| accepted → `SendCreditTransfer` in outbox p99 | ≤ 1 s |
| incoming `pacs.008` received → answer sent p99 | ≤ 2 s |
| accepted → `SETTLED` p99 (simulator beneficiary delay 300 ms) | ≤ 3 s |
| answers decided by `DEADLINE` under peak | 0 |
| `csm.*` consumer lag | < 1 s (alert threshold of ADR-002) |
