# 20 Testing strategy and scenario catalogue

Money correctness and deadline behaviour are the two things that must never regress. The test pyramid is built
around them: many fast tests of the domain rules, a component suite per service that proves its acceptance criteria,
and a small, deliberate set of end-to-end scenarios that cover every use-case flow (§8) and end with global
invariant checks (§3).

## 1. Test levels

| Level | Where | Tooling | What it proves |
|---|---|---|---|
| Unit | each module, `src/test` | JUnit 5, AssertJ, `MutableClock` | domain rules: state machine transitions, posting engine, matchers, value objects, reason mapping |
| Property-based | `payment-domain`, ledger domain, `iso20022-model` | jqwik | invariants: Σ DR = Σ CR; available ≥ 0; every builder output is XSD-valid; IBAN round trips; `Money.parse` ∘ `toDecimalString` is identity |
| Stateful property-based | orchestrator `domain.payment`, gateway answer coordinator | jqwik stateful (action sequences) | for random interleavings of CSM events, timer fires and action results ([11 §6.6](11-payment-orchestrator.md#66-concurrency)): a final state is never left; exactly one final event; every rejection after a possible hold / pending credit has `RELEASE_HOLD` / `CANCEL_CREDIT` as next action; result equals the §6.4 / §6.5 tables in either order |
| Component (service) | each service, `src/integrationTest` (Failsafe) | Spring Boot test, Testcontainers PostgreSQL + Kafka, WireMock for REST neighbours (business responses must match a pact, §10.4) | the service's acceptance criteria (`<SVC>-AC-nn`) in isolation |
| Concurrency | ledger, gateway, payment-api, `messaging` | component setup + two app contexts / latches | LED-AC-09, -10, GW-AC-12, API-AC-10, MSG-AC-03, MSG-AC-08: races resolved by the DB as specified |
| Serialisation contract | `contracts`, `iso20022-model` | golden samples, round-trip tests | shared records and ISO messages serialise stably: every consumer reads the golden samples of the current **and previous** version; unknown fields are ignored |
| Consumer-driven contract (Pact) | consumer: client adapter / listener module; provider: `src/integrationTest` | Pact JVM, Pact specification V4 (HTTP + message) | every internal REST and Kafka integration gives each consumer the responses and messages it relies on — same commit, and across the previous release in both directions (§10) |
| API description | every service | springdoc, openapi-diff | the checked-in OpenAPI matches the code (drift check); the customer-facing APIs (payment-api, notification subscriptions) have no breaking change against the last release |
| Schema compatibility | each service, CI on `main` | Flyway + previous release image | the previous release's component suite passes against the new migrations (24/7 rule, [00 §9](00-engineering-baseline.md#9-database-and-migrations)) |
| Security | each service (component) + system tests | Spring Security test, authorisation matrix generated from OpenAPI | every endpoint × (no token, wrong scope, wrong role, foreign account) → `401` / `403` / `404` per [00 §7, §12](00-engineering-baseline.md#121-authentication-and-authorisation); DOCTYPE rejected; internal APIs unreachable from outside the compose network; from M5 every internal endpoint × (no token, wrong `aud`, missing scope) → `401` / `403` ([ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md)); a service principal producing to a topic it does not own is refused (SEC-01); requests over the limits get `429` (SEC-04) |
| Architecture | each service | ArchUnit | layering, no `Instant.now()`, no `double` money |
| Traceability | CI step | `tools/traceability-check` over `specs/*.md` and test sources | every `<SVC>-AC-nn` and every `E2E-*` / `CH-*` id has at least one test carrying it in its name or `@Tag`; every integration `P-nn` / `M-nn` of §10.1 has a consumer pact test and a provider verification. Ids whose milestone is not delivered yet are listed in the pending-ids baseline `tools/traceability-check/pending-ids.txt`, which only shrinks: an id leaves it in the change that adds its test, and enters it only in the change that adds it to the specs (compared with `main` at the merge base) |
| System (end-to-end) | `tests/system-tests` | JUnit 5 + Testcontainers Compose module, simulator control API, mock OAuth2 tokens, webhook receiver stub | scenario catalogue §4 |
| Failure injection | `tests/system-tests` (`@Chaos`, nightly) | Toxiproxy between services / Kafka / DB, container pause / kill | §5 |
| Performance | `tests/system-tests/perf` (nightly) | Gatling | §6 |
| Mutation | nightly | PIT | money- and deadline-critical code is actually asserted on (§7) |
| Telemetry contract | each service, component test | scrape `/actuator/prometheus` | every metric of [25 §11](25-service-levels.md#11-metrics-to-add) and of the service spec exists with its tags and an exact histogram bucket at every SLO threshold of [25 §5](25-service-levels.md#5-component-slos); no high-cardinality tag ([00 §11](00-engineering-baseline.md#11-logging-tracing-metrics)) |
| Alert rules | `deploy/slo`, CI | `promtool test rules` | the burn-rate rules generated from the SLO files fire and reset as [25 §9.1](25-service-levels.md#91-burn-rate-alerts-every-slo-with-a-percentage) specifies (both windows, 100-event minimum); every cause alert of [25 §9.2](25-service-levels.md#92-cause-alerts-page-regardless-of-budget) fires on its condition and stays silent in the tolerated cases (relay overlap or gap < 5 s); fixtures include recorded series of CH runs |
| Configuration consistency | parent, unit test over the default `application.yml` files and `deploy/slo` | JUnit | `tpe.messaging.inbox.retention` above the retention of every consumed `delete` topic ([01 §3.1](01-shared-libraries.md#31-tables-flyway-classpathdbmigrationmessaging)); the timing order of §2.1 and the timeout headroom of [25 §7](25-service-levels.md#7-dependency-check-timeouts-against-slos): every caller timeout lies above the callee's highest-percentile SLO threshold |
| Synthetic probes | probe code of [25 §9.3](25-service-levels.md#93-low-traffic-and-synthetic-probes), run against the system-test stack on every PR | the production probes | each probe passes on a healthy stack and fails when its target is stopped |
| Recovery drill | quarterly, staging | point-in-time restore of each tier-0 database | the restore completes, INV-1, -2 and -12 hold on the restored ledger, the restore time is measured and published ([25 §8](25-service-levels.md#8-durability-and-recovery)) |

## 2. Rules for writing tests

- Tests never sleep for business time. Services under component test get a `MutableClock` bean; system tests use
  the `systemtest` profile (§2.1) and Awaitility with explicit upper bounds **derived from that profile** (e.g.
  `deadline-offset + investigation.budget + 2s`), never magic numbers.
- One test per acceptance criterion at minimum, named after it (`ORC_AC_09_deadlineInSentToCsmStartsInvestigation`).
  E2E and chaos tests carry their id the same way (`E2E_O_12_gatewayDownPastSendBy`).
- Every system test ends with the **per-test global invariant checks** (§3) — failures there are always release blockers.
- Every E2E test asserts what the customer sees, not only internal state: the status through payment-api
  (UC-07 mapping) and the webhook outcome (INV-13).
- Every E2E test that ends in a rejection also asserts the `cause` tag of `tpe.payment.rejected`
  ([25 §11](25-service-levels.md#11-metrics-to-add)), because a wrong tag silently moves SLO-ORC-06: `BUSINESS` for a
  refusal by a rule or an account state (`AM04`, `AC01` / `AC04` / `AC06`, `FR01` / `RR04` from a rule), `TECHNICAL`
  for our own failures (`MS03`, `AB03` with `NOT_SENT`, `FR01` after a risk timeout, incoming `AB05` decided by our
  answer deadline), `EXTERNAL` for rejections received from the CSM or the Beneficiary PSP (including outgoing `AB05`).
  VoP scenarios assert the `cause` tag of `tpe.vop.requests` the same way (`LOCAL` vs `EXTERNAL`).
- Test data: unique IBANs / customer ids per test (`TestData.newCustomer()`), no shared fixtures that tests mutate.
  Shared resources — the `CSM_SETTLEMENT` and `FUNDING` accounts, Kafka topics, the simulator — are asserted on only
  for the test's own `paymentId`s / `TxId`s, never as totals (totals are checked by the nightly soak only).
- System tests are order-independent and may run in parallel per class. Simulator scenarios are set per creditor
  IBAN, so tests do not interfere. `POST /sim/v1/reset` is called once at suite start, never during a run.
- A system-test failure attaches a debug bundle for the test's transactions: payment transitions, gateway journal
  (`GET /v1/journal?txId=`), simulator received log, ledger entries, webhook deliveries.
- No automatic retries of failed system tests. A flaky test is quarantined (`@Quarantined` + ticket) within one
  working day and fixed or deleted within two weeks. Invariant checks are never quarantined.

### 2.1 `systemtest` profile

Short deadlines keep the suite fast. They keep the ordering of the production values, so the same races occur.

| Service | Property | `systemtest` | Default | Why |
|---|---|---|---|---|
| orchestrator | `tpe.outgoing.send-by-offset` / `deadline-offset` | `3s` / `6s` | `10s` / `20s` | |
| orchestrator | `tpe.outgoing.hold-give-up-margin` | `1s` | `2s` | |
| orchestrator | `tpe.incoming.verdict-give-up-margin` | `500ms` | `1s` | |
| orchestrator | `tpe.incoming.settlement-overdue` | `5s` | `30s` | E2E-I-08 |
| orchestrator | `tpe.investigation.backoff` / `budget` | `500ms,1s` / `10s` | `5s,…` / `1h` | E2E-O-11 |
| csm-gateway | `tpe.csm.answer-deadline-offset` | `2s` | `5s` | |
| csm-gateway | `tpe.csm.answer-transmit-window` | `1s` | `1s` | counted from `answer_deadline`; deadline + window must not exceed the simulator `answer-timeout` |
| csm-simulator | `tpe.sim.timeout-deadline` / `answer-timeout` | `6s` / `3s` | `20s` / `6s` | equal to the orchestrator deadline, as in production |
| vop-service | `tpe.vop.reference-validity` | `5s` | `30m` | E2E-O-17 (expired reference) |
| risk-service | `tpe.risk.sanctions.poll-interval` | `1s` | `15m` | E2E-O-06, -I-11, -S-01, -S-03 |
| notification-service | `tpe.notification.retry-schedule` / `retry-window` | `200ms,500ms,1s` / `30s` | `10s,…` / `24h` | E2E-N-01, -N-02 |
| ledger-service | `tpe.ledger.snapshot.interval` | `5s` | `15m` | snapshots are exercised during system tests (LED-AC-11) |

Ordering rule, checked by a unit test over the default and the `systemtest` profile files: `send-by-offset < deadline-offset =
sim.timeout-deadline`; `answer-deadline-offset + answer-transmit-window ≤ sim.answer-timeout`;
`settlement-overdue > sim.beneficiary-delay`.

Performance runs (§6) use the **default** values, never this profile.

## 3. Global invariants

Per-test invariants are asserted after every system test for the test's own transactions. The nightly soak (§6)
asserts all of them every 5 minutes over the whole database, plus the totals marked *soak*. In production the
money-related ones run continuously as reconciliation checks ([25 §6](25-service-levels.md#6-correctness-invariants-zero-budget)).

Each check has one implementation, used by the system tests, the soak and the production checks, and each has a
**negative test**: a test database seeded with exactly that violation must make the check fail. A check that cannot
fire would report a clean system forever.

| # | Invariant | How it is checked |
|---|---|---|
| INV-1 | Every journal entry balances | SQL on ledger DB |
| INV-2 | No customer account has `available < 0` or `booked < 0` | SQL |
| INV-3 | Every final outgoing payment has no `ACTIVE` hold; `SETTLED` ⇔ hold `SETTLED` (on-us: settled on-us); `REJECTED` ⇔ hold `RELEASED`, tombstoned or never created | join orchestrator DB ↔ ledger DB via admin APIs |
| INV-4 | Every final incoming payment has no `ACTIVE` pending credit; `SETTLED` ⇔ `CONFIRMED`; `REJECTED` ⇔ `CANCELLED`, tombstoned or never created | same |
| INV-5 | No `pacs.008` was transmitted after its `sendBy`, and none for a transfer the gateway records as `NOT_SENT` | simulator received-log vs gateway `outgoing_transfers` |
| INV-6 | Exactly one answer `pacs.002` per incoming (`debtorAgentBic`, `TxId`) ([ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md)), and the orchestrator state agrees with it | simulator log vs orchestrator |
| INV-7 | Exactly one `PaymentSettled` / `PaymentRejected` per final payment, none for a non-final payment | test consumer on `payments.events` |
| INV-8 | Each `SETTLED` non-on-us outgoing payment has exactly one `CR CSM_SETTLEMENT` posting of its amount; each `SETTLED` incoming payment exactly one `DR CSM_SETTLEMENT`; on-us and rejected payments none. *Soak:* derived balance (Σ CR − Σ DR, [12 §6](12-ledger-service.md)) = Σ settled outgoing − Σ settled incoming | ledger entries by `paymentId` |
| INV-9 | No DLT message, no unexpected exception case | Kafka DLT topics, case API |
| INV-10 | **Our ledger agrees with the CSM**: for every `TxId` the simulator settled, the ledger has the matching `CSM_SETTLEMENT` posting; for every `TxId` it did not settle, none | simulator settlement log ([17 §3.2](17-csm-simulator.md#32-scheme-behaviour), `GET /sim/v1/settlements`) vs ledger |
| INV-11 | Nothing is stuck: every payment is final or has an `OPEN` exception case; no `SCHEDULED` timer belongs to a final payment; no `pending_action` is overdue by more than `lease + max-backoff`; no gateway row is `PENDING` past its give-up window | SQL / admin APIs after quiescence |
| INV-12 | Per customer account: `booked` = Σ postings; `available` = `booked` − Σ `ACTIVE` holds; pending credits change neither | SQL |
| INV-13 | Webhooks match outcomes: one delivery per subscription for every final outgoing payment (on-us `SETTLED`: payer and payee); one for every `SETTLED` incoming payment; none for a `REJECTED` incoming payment ([Q-19](40-open-questions.md#q-19-notifications-for-rejected-incoming-payments)); every signature verifies | webhook receiver stub |
| INV-14 | No personal data in logs: no IBAN or name generated by `TestData` appears in any container log at INFO or above ([00 §11](00-engineering-baseline.md#11-logging-tracing-metrics)) | log scan, once per suite |

## 4. End-to-end scenario catalogue

Each scenario lists the setup through the simulator control API and the expected outcome. `@smoke` scenarios run on
every PR. New ids are appended; existing ids are never renumbered ([30](30-delivery-plan.md) refers to them).

### Outgoing

| Id | Scenario | Setup | Expected |
|---|---|---|---|
| E2E-O-01 `@smoke` | Happy path to another PSP | default `ACCEPT` | `202` → `ACSC` (JSON and `pain.002`); payer debited; CSM settlement credited; webhook to payer |
| E2E-O-02 `@smoke` | On-us | payee is our customer (VoP answered locally) | `ACSC`; single entry Dr payer / Cr payee; webhooks to both; no `pacs.008` |
| E2E-O-03 | Insufficient funds | balance < amount | `RJCT AM04`; no hold left; webhook to payer |
| E2E-O-04 | Payer blocked / closed | block payer (`OPERATOR`); variant: close payer | `RJCT AC06` / `RJCT AC04` |
| E2E-O-05 | Fraud rule | amount > 50 000 | `RJCT FR01`; no ledger call |
| E2E-O-06 | Non-EU sanctions creditor | UN list contains creditor name | `RJCT RR04` |
| E2E-O-07 `@smoke` | Beneficiary rejects | `REJECT:AC04` | `RJCT AC04`; hold released |
| E2E-O-08 | Beneficiary timeout | `TIMEOUT` | `RJCT AB05` via CSM `pacs.002`, or via investigation — either way released, never settled |
| E2E-O-09 | No `pacs.002`, settled | `SILENT` | `INVESTIGATING` (status `PDNG`) → `pacs.028` → `ACSC`; payer debited |
| E2E-O-10 | No `pacs.002`, unknown | `LOST` | `INVESTIGATING` → `UNKNOWN_TX` after deadline → `RJCT AB05`; released |
| E2E-O-11 | Investigation unanswered | `SILENT` + `INVESTIGATION_SILENT` | retries, then `UNRESOLVED_INVESTIGATION` case; hold stays; status `PDNG`; operator resolves → final. Variant: operator chooses `RETRY_INVESTIGATION` after clearing `INVESTIGATION_SILENT` → a new `pacs.028` reaches the simulator → `ACSC` |
| E2E-O-12 | Gateway down past `sendBy` | stop csm-gateway before submit, start it between `sendBy` and the deadline | `RJCT AB03` from `SENT_TO_CSM`; simulator received nothing |
| E2E-O-13 | CSM unreachable past `sendBy` | simulator outage `DOWN` (variant: `RESET`) from submit until after the deadline, then `UP` | no `pacs.008` after `sendBy`; investigation → `UNKNOWN_TX` → `RJCT AB05`; released |
| E2E-O-14 | Late contradicting `pacs.002` | `REJECT` then inject `ACSC` via raw endpoint; variant: `ACCEPT` then inject `RJCT` | first outcome kept (`RJCT` / `ACSC`); `CONTRADICTING_CSM_OUTCOME` case; no money movement after the first outcome |
| E2E-O-15 | On-us payee blocked | block payee | `RJCT AC06`; hold released |
| E2E-O-16 | Idempotent retry | submit twice with same key; once with lost response (Toxiproxy) | one payment |
| E2E-O-17 | VoP required | no / expired / mismatching reference | `422 VOP_REQUIRED`; no payment |
| E2E-O-18 | On-us payee unknown / closed / risk deny | IBAN with our bank code and no account; closed payee; payee trips `MULE_NEW_ACCOUNT` | `RJCT AC01` / `AC04` / `FR01`; hold released |
| E2E-O-19 | Risk `REVIEW` | first payment of 6 000 EUR to a counterparty with VoP `CLOSE_MATCH` (`NEW_PAYEE_HIGH_AMOUNT`); variant: VoP `MATCH` | `RJCT FR01`; no ledger call. Variant: `ACSC` |
| E2E-O-20 | Late `pacs.002` during investigation | `LATE_STATUS:<deadline + 1s>` | `INVESTIGATING` → `ACSC` from the late `pacs.002`; the `pacs.028` answer is a no-op; one settlement |
| E2E-O-21 | Gateway down past the deadline | stop csm-gateway before submit, start it after the deadline | `INVESTIGATING` → `CreditTransferNotSent` → `RJCT AB03`; no `pacs.028`, simulator received nothing |

### Incoming

| Id | Scenario | Setup | Expected |
|---|---|---|---|
| E2E-I-01 `@smoke` | Happy path | incoming payment to open account | `ACCP` then `ACSC`; payee credited; webhook to payee |
| E2E-I-02 `@smoke` | Unknown account | creditor IBAN has our bank code but no account | `RJCT AC01`; no pending credit |
| E2E-I-03 | Blocked account | block payee | `RJCT AC06` |
| E2E-I-04 | Closed account | close payee | `RJCT AC04` |
| E2E-I-05 | Risk deny | mule rule triggers (account opened today has already received more than 10 incoming payments within 24 h) | `RJCT FR01` |
| E2E-I-06 | Orchestrator slow | Toxiproxy delay orchestrator ↔ Kafka > answer deadline | gateway sends `RJCT AB05` itself; late verdict ignored; pending credit cancelled |
| E2E-I-07 | CSM rejects after `ACCP` | `REJECT_AFTER_ACCP` | `REJECTED`; pending credit cancelled; payee not credited, not notified |
| E2E-I-08 | No settlement confirmation | `NO_CONFIRMATION` | `INVESTIGATING` → `pacs.028` → `ACSC` → `SETTLED`; payee credited only then |
| E2E-I-09 | Invalid XML | raw invalid `pacs.008`; variant: with a DOCTYPE | `RJCT FF01`; no payment created; no entity resolved |
| E2E-I-10 | Duplicate `pacs.008` | send the same message twice: before our answer, and after it | one payment, one credit; early duplicate dropped; the same answer re-sent for the late duplicate |
| E2E-I-11 | Non-EU sanctions debtor | UN list contains debtor name | `RJCT RR04`; no pending credit |
| E2E-I-12 | Same `TxId` from two PSPs | two raw `pacs.008` with equal `TxId` and different `DbtrAgt` BICs | two independent payments, two answers, two credits ([ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md)) |
| E2E-I-13 | Incoming without VoP and notifications | stop vop-service and notification-service | incoming payments answered and settled within budget; webhooks delivered after restart (UC-06 NFR) |

### VoP, sanctions, accounts, notifications

| Id | Scenario | Expected |
|---|---|---|
| E2E-V-01 | VoP against own customer and counterparty: match / close match / no match / unknown | results as seeded; `CLOSE_MATCH` returns actual name; own customer answered without calling the simulator |
| E2E-V-02 | Counterparty VoP slow (`VOP_SLOW`) | `NOT_POSSIBLE` within 5.2 s; payment still possible |
| E2E-V-03 | Counterparty asks our responder: known, unknown and closed IBAN | correct result (`NOT_POSSIBLE` for unknown / closed); audit row hashed |
| E2E-S-01 | New EU list with a customer | provisional block within minutes; outgoing and incoming payments `AC06`; confirm → `SANCTIONS`; dismiss → unblocked and not re-raised |
| E2E-S-02 | Provider outage | previous list kept; alert metric |
| E2E-S-03 | Listed person opens a new account | screened on `AccountOpened`; provisionally blocked; first payment `AC06` |
| E2E-A-01 | Open, fund, block, unblock, close | events reach vop / risk projections; close refused while not empty |
| E2E-N-01 | Webhook receiver down for 10 s | delivered after recovery; payments unaffected |
| E2E-N-02 | Webhook receiver down longer than `retry-window` | delivery `FAILED`, visible in the admin API; payments unaffected |

## 5. Failure-injection scenarios

Run nightly with a steady load of 20 payments/s (both directions) and assert the global invariants after the system
is quiesced.

Rules:

- **Environment.** Not the local compose stack. Tier-0 databases run as primary + synchronous standby with automatic
  failover ([25 §8](25-service-levels.md#8-durability-and-recovery)); Kafka runs 3 brokers with RF 3 and
  `min.insync.replicas=2` ([02 §2](02-messaging-contracts.md#2-topics)); every tier-0 service runs two instances;
  Prometheus evaluates the generated SLO and cause alert rules ([25 §9](25-service-levels.md#9-alerting)) and sends
  them to an Alertmanager receiver stub. Without this, CH-06, CH-07 and the page assertions test nothing.
- **Timing.** Default (production) values, not `systemtest`: the scenarios check that the real margins of
  [25 §3.2](25-service-levels.md#32-outgoing-latency-budget-scheme-target-10-s-sendby-10-s-deadline-20-s) absorb the
  failure.
- **Budget.** Each run reports the error budget consumed per SLO of [25 §5](25-service-levels.md#5-component-slos),
  computed with the SLI queries of `deploy/slo/<service>.yml` over the run window ([25 §12](25-service-levels.md#12-verification)).
  The "Specifically checks" column holds hard expectations; budget figures are compared with earlier runs, and a rise
  of more than 50 % is investigated.
- **Pages.** Each scenario lists the pages it must raise. Any other page during the run fails it. The recorded
  series of each run are kept as fixtures for the alert-rule tests (§1).

| Id | Injection | Specifically checks | Expected pages |
|---|---|---|---|
| CH-01 | Kill a random payment-orchestrator instance every 30 s | saga resume (ADR-003, ADR-008); no `AB03` (SLO-GW-03), no `DEADLINE` answers (SLO-GW-05) | none |
| CH-02 | Kill a csm-gateway instance during sends every 30 s (one instance always alive) | INV-5; transmissions claimed by the killed instance are taken over after the 2 s lease, before `sendBy`: no `AB03`, no `DEADLINE` answers. The never-attempted path of ADR-011 needs every gateway down until `sendBy`; GW-AC-04, E2E-O-12 and -O-21 cover it | none |
| CH-03 | Block Kafka produce of every orchestrator instance for 15 s (Toxiproxy) | ADR-004 send-by race: no `pacs.008` after `sendBy`; payments whose `sendBy` passed during the block end `AB03` with `NOT_SENT`. Leadership does not flap: failed sends count as relay progress, `tpe.relay.active` stays on one instance ([ADR-006 amendment](../architecture/decisions/ADR-006-ordered-outbox-relay.md#amendment-2026-10-22-bounded-failover)) | burn of SLO-GW-03 / SLO-ORC-06 allowed |
| CH-04 | Delay `csm.outbound.commands` consumption past the answer deadline | ADR-004 answer race: INV-6 | burn of SLO-GW-05 / SLO-GW-09 allowed |
| CH-05 | Ledger latency 2 s / ledger down 30 s | holds give up correctly (`MS03`), settlements retried, no stuck holds after recovery; technical rejections only during the outage (SLO-ORC-06); on-us payments past `FUNDS_RESERVED` end on a ledger answer, never `AB03` ([Q-15](40-open-questions.md#q-15-on-us-payments-and-the-20-s-deadline)) | burn of SLO-LED-01 / SLO-ORC-06 allowed |
| CH-06 | Fail over the primary of one tier-0 database (each run takes the next of `payments`, `ledger`, `csm`, `risk`) | RPO 0: every request acknowledged before the failover (`201` / `202` / ledger `200` / Kafka ack) exists afterwards; errors only within RTO ≤ 30 s, measured and reported; consumers block and resume, no DLT | burn of the affected service's availability SLO; "Synchronous standby missing" until the old primary rejoins |
| CH-07 | Restart one Kafka broker; variant: lose one broker for 10 min | no message loss (INV-7 counts); produce stays available with `acks=all` (SLO-PLT-02) | none |
| CH-08 | Clock skew +200 ms on gateway (twice the limit of [00 §6](00-engineering-baseline.md#6-time), on purpose) | margins hold; no invariant broken | "Clock offset" SEV-1 |
| CH-09 | Reset the consumer groups of orchestrator, gateway and notification-service to the start of the test window — the recovery runbook of [25 §8](25-service-levels.md#8-durability-and-recovery) | redelivery has no business effect: no new `pacs.008` / answer, INV-7 and INV-13 counts unchanged; `tpe.inbox.duplicates` rises | none |
| CH-10 | risk-service down for 30 s | outgoing `RJCT FR01` (fail closed); incoming answered `RJCT` (`FR01` or `AB05`) before the answer deadline; nothing settled without an `ALLOW`; technical rejections only during the outage (SLO-ORC-06) | burn of SLO-RSK-01 / SLO-ORC-06 allowed |
| CH-11 | Kill the active outbox relay of orchestrator and gateway | per-key order kept: no `RequestPaymentStatus` processed before its `SendCreditTransfer` (ADR-006); no `AB03`, no `DEADLINE` answers | none |
| CH-12 | Stop every notification-service instance for 10 min, then start them | isolation (UC-08 NFR): no tier-0 SLI of [25 §5](25-service-levels.md#5-component-slos) moves during the outage; after restart the `payments.events` lag is back under 10 s within 5 min and INV-13 holds | SLO-NTF-01 fast burn only |
| CH-13 | Lose the active outbox relay without closing its socket, for orchestrator and gateway: (a) `docker pause` the leader for 15 s, then unpause; (b) Toxiproxy blackhole on the leader's DB traffic for 15 s | another instance publishes within 5 s (SLO-MSG-02); no `AB03` (SLO-GW-03) and no `DEADLINE` answers (SLO-GW-05); after (a) the old leader stops before its next batch: per-key order kept, `tpe.inbox.duplicates` may rise, INV-7 counts unchanged ([ADR-006 amendment](../architecture/decisions/ADR-006-ordered-outbox-relay.md#amendment-2026-10-22-bounded-failover)) | none: neither "No active relay" (gap ≤ 5 s) nor "Two active relays" (overlap < 5 s) |
| CH-14 | Stop every vop-service instance for 2 min | tier-0 dependency ([25 §2](25-service-levels.md#2-service-tiers)): initiation answers `503 VOP_UNAVAILABLE` with `Retry-After`; a retry with the same key after recovery gives `202` and one payment; accepted and incoming payments are unaffected | burn of SLO-API-01 / -05 and SLO-VOP-01 / -02 allowed |
| CH-15 | Rolling upgrade from the last release to this commit under the chaos load: migrations first, then every service one instance at a time | the 24/7 rule end to end: mixed versions ([00 §9](00-engineering-baseline.md#9-database-and-migrations), §10.3), consumer rebalances ([25 §13](25-service-levels.md#13-risks-and-what-to-revisit) R-2): no `AB03`, no `DEADLINE` answers, `csm.*` lag within SLO-ORC-09 / SLO-GW-09 | none |
| CH-16 | Identity provider unavailable for 15 min with internal authentication enabled (from M5) | internal calls keep working for the whole outage (30 min tokens refreshed at 50 %, [Q-24](40-open-questions.md#q-24-service-token-lifetime-and-idp-availability)); no payment-path SLI moves; the refresh-failure alert fires after 5 min ([25 §13](25-service-levels.md#13-risks-and-what-to-revisit) R-3) | token refresh failure |
| CH-17 | Publish one undeserialisable record to `csm.inbound.events` | the record reaches the DLT after 3 attempts and the next message is processed (MSG-AC-04 at system level); a `DLT_MESSAGE` case is opened; INV-9 is waived for this record only | "DLT message" SEV-2 |

## 6. Performance

Target load ([Q-13](40-open-questions.md#q-13-target-load)): 100 payments/s sustained, 300/s peak for 5 min, 60 / 40
outgoing / incoming mix, 10 % on-us, 5 % scenario rejections (`REJECT:AC04`) so compensation runs under load.

Rules:

- Same environment as §5 and default timing configuration (not `systemtest`); the numbers feed the `sendBy` /
  answer-deadline tuning of M5 and the SLO recalibration of [25 §10](25-service-levels.md#10-error-budget-policy).
- SLOs are evaluated from the services' own metrics with the SLI queries of `deploy/slo/<service>.yml` — the queries
  that drive production alerts, so the run validates them too. Gatling's client-side percentiles are reported
  alongside; a gap of more than 50 ms at p99 between the two views of an endpoint is investigated
  ([25 §13](25-service-levels.md#13-risks-and-what-to-revisit) R-4).
- A run fails if any target below is missed. Every SLO is reported by id with the value achieved, because every SLO
  must stay looser than what the component achieves ([25 §1](25-service-levels.md#1-terms-and-rules)).
- The simulator's own processing overhead beyond its configured delays is reported; above 10 ms p99 the run is
  invalid ([25 §5.10](25-service-levels.md#510-csm-simulator)).
- Test data must not trip velocity or mule rules unintentionally: at least 12 000 funded payers (≤ 20 outgoing
  payments each per hour at 60/s), amounts sized so no payer exceeds `VELOCITY_AMOUNT_24H` over the run, and
  creditor accounts older than 7 days, all seeded before the run. Rules stay enabled so their cost is measured.
- Nightly: 30 min sustained + 5 min peak, invariants (§3, including *soak* totals) every 5 min. Weekly: 8 h soak.
- Weekly **capacity test**: from 100/s, add 50/s every 5 min until an SLO is missed or errors appear. It records the
  saturation throughput of every tier-0 service and the maximum throughput of each outbox relay — the denominators of
  the capacity alert ([25 §9.2](25-service-levels.md#92-cause-alerts-page-regardless-of-budget)) and the check of the
  4–8× relay headroom assumed in R-5.

Targets: the latency SLOs of [25 §5](25-service-levels.md#5-component-slos) at the same thresholds, plus stricter
zero targets where a bad event costs a scheme obligation. Availability SLOs of tier 0 are reported and must hold
over the run window.

| Id | Measure | Target (main / tail) |
|---|---|---|
| SLO-API-02 | `POST /v1/payments` | 99 % ≤ 300 ms / 99.9 % ≤ 1 s |
| SLO-API-04 | `GET /v1/payments/{id}` | 99 % ≤ 200 ms |
| SLO-API-05 | `POST /v1/payee-verifications` | 99.9 % ≤ 5.5 s |
| SLO-ORC-02 | orchestrator submit and status | 99 % ≤ 50 ms |
| SLO-ORC-03 | accepted → `SendCreditTransfer` in outbox; on-us accepted → `SETTLED` | 99 % ≤ 1 s / 99.99 % ≤ 5 s |
| SLO-ORC-04 | `CreditTransferReceived` consumed → verdict in outbox | 99 % ≤ 1.2 s / 99.99 % ≤ 3 s |
| SLO-ORC-05 | CSM outcome consumed → ledger effect and final event | 99 % ≤ 500 ms / 99.99 % ≤ 5 s |
| SLO-ORC-07, CUJ-3 | outgoing accepted → final (simulator beneficiary delay 300 ms) | 99 % ≤ 3 s / 99.9 % ≤ 20 s |
| SLO-ORC-08 | timer and sweeper lag ≤ 500 ms | 99.9 % of minutes |
| SLO-ORC-09, SLO-GW-09 | `csm.*` consumer lag ≤ 1 s | 99.9 % of minutes |
| SLO-LED-02 | posting operation, service-side | 99 % ≤ 50 ms / 99.99 % ≤ 200 ms |
| SLO-VOP-01 | reference check | 99 % ≤ 50 ms |
| SLO-VOP-02 | own-bank verification | 99 % ≤ 200 ms |
| SLO-VOP-04 | responder at 200 req/s (separate load step) | 99 % ≤ 1 s / 99.9 % ≤ 2 s |
| SLO-RSK-02 | risk assessment, service-side | 99 % ≤ 50 ms / 99.99 % ≤ 250 ms |
| SLO-GW-02 | command consumed → `pacs.008` accepted by the CSM | 99 % ≤ 100 ms / 99.99 % ≤ 2 s |
| SLO-GW-03 | `pacs.008` not sent (`AB03`) under peak | 0 |
| SLO-GW-04 | inbound `pacs.008` → event in outbox | 99 % ≤ 50 ms |
| SLO-GW-05 | answers decided by `DEADLINE` under peak | 0 |
| SLO-GW-06 | `pacs.008` received → answer transmitted (`answer.margin` ≥ 3 s) | 99 % ≤ 2 s / 99.99 % before the deadline |
| SLO-GW-07 | answer-deadline worker lag | 99 % of minutes ≤ 100 ms |
| SLO-GW-08 | inbound `pacs.002` → event in outbox | 99 % ≤ 100 ms |
| SLO-MSG-01 | outbox insert → Kafka ack | 99 % ≤ 50 ms / 99.99 % ≤ 1 s |
| SLO-MSG-03 | `payments.events` lag ≤ 10 s | 99.9 % of minutes |
| SLO-NTF-01 | final event → first webhook attempt | 99 % ≤ 5 s / 99.9 % ≤ 30 s |
| RSK-AC-10 | full sanctions run, 100 000 customers × 5 000 entries | ≤ 6 min |

## 7. Coverage targets

Line coverage is a weak signal for this system; traceability and mutation score are the gates.

| Target | Gate |
|---|---|
| Every `<SVC>-AC-nn` has a named test | CI traceability step fails the build |
| Every use-case step and alternative flow maps to a test | §8 reviewed with every change to a use case or spec |
| Every scenario in §4 / §5 is assigned to a milestone exit in [30](30-delivery-plan.md) | review |
| Branch coverage of `domain` packages and `libs/payment-domain` | ≥ 90 % (JaCoCo, build fails below) |
| Mutation score (PIT) of `payment-domain`, ledger posting engine, orchestrator `domain.payment`, gateway answer coordinator / send-by guard | ≥ 85 %; surviving mutants reviewed weekly |
| Every integration of §10.1 has a pact produced by the build and verified by its provider(s), in all three checks of §10.3 | CI traceability step and `-Ppact-verify` fail the build |
| Every response branch a consumer handles has an interaction | review; for P-03 / P-04 checked row by row against the mapping tables of [11 §6.2](11-payment-orchestrator.md#62-pending-actions) |
| Every business WireMock stub matches a pact interaction | `StubConformanceTest` (§10.4) |
| Every tier-0 latency SLO of [25 §5](25-service-levels.md#5-component-slos) is reported by id in the nightly performance run | the run fails if one is missing |
| Every generated burn-rate rule and every cause alert has an alert-rule test | CI |
| Every invariant check has a negative test (§3) | CI |
| Every CH scenario declares its expected pages, and every run reports the error budget consumed per SLO | review; the run fails on an undeclared page |
| Other packages | no numeric gate; covered by component tests |

## 8. Use-case traceability

| Use case flow | Component / AC | E2E / chaos |
|---|---|---|
| **UC-01** main | API-AC-01, -02, ORC-AC-01 | O-01 |
| 1a not owner | API-AC-05 | — |
| 2a / 2b idempotency | API-AC-03, -04, -10 | O-16 |
| 3a validation (incl. `FF01` `pain.002`) | API-AC-06, -11 | — |
| 4a VoP reference | API-AC-07, VOP-AC-03 | O-17 |
| 5a orchestrator down | API-AC-08 | — |
| 6a lost response | API-AC-08, ORC-AC-01 | O-16 |
| **UC-02** main (local / remote) | VOP-AC-01 | V-01 |
| 1a invalid IBAN | API-AC-06 | — |
| 3a responder slow | VOP-AC-02 | V-02 |
| 5 / 7 reference binding | VOP-AC-03 | O-17 |
| **UC-03** main | VOP-AC-04 | V-03 |
| 2a unknown / closed | VOP-AC-05 | V-03 |
| 3a organisation id | VOP-AC-06 | — |
| audit, retention | VOP-AC-08 | V-03 |
| **UC-04** main | ORC-AC-02, GW-AC-01, LED-AC-05 | O-01 |
| 2a `FR01` / `RR04` | ORC-AC-03, RSK-AC-01, -03 | O-05, O-06 |
| 2b `REVIEW` | ORC-AC-21, RSK-AC-11 | O-19 |
| 2c risk unavailable | ORC-AC-04 | CH-10 |
| 3a `AM04` | ORC-AC-05, LED-AC-03 | O-03 |
| 3b blocked / closed payer | ORC reason mapping | O-04 |
| hold technical failure → `MS03` | ORC-AC-22 | CH-05 |
| 4a on-us | ORC-AC-11 | O-02 |
| 4a-1 on-us payee refused | ORC-AC-12, LED-AC-06 | O-15, O-18 |
| on-us exempt from deadline (Q-15) | ORC-AC-23 | CH-05 |
| 5a send-by passed | ORC-AC-06, -07, GW-AC-02, -03, -04 | O-12, O-13, CH-02, CH-03 |
| 6a CSM rejects | ORC-AC-08 | O-07 |
| 8a late / contradicting `pacs.002` | ORC-AC-10, GW-AC-13 | O-14 |
| crash at any step | ORC-AC-13 | CH-01 |
| **UC-05** 1 deadline → investigation | ORC-AC-09 | O-09 |
| 2a never sent | GW-AC-05 | O-21 |
| 3–5 `pacs.028` answered | GW-AC-06 | O-09 |
| 4a / 4b unanswered → case → operator | ORC-AC-09, -20, -27 | O-11 |
| 4c `UNKNOWN_TX` | GW-AC-07, SIM-AC-04 | O-10, O-13 |
| late `pacs.002` during investigation | ORC-AC-24, SIM-AC-05 | O-20 |
| **UC-06** main | GW-AC-08, ORC-AC-14, LED-AC-07 | I-01 |
| 1a invalid | GW-AC-09, -14 | I-09 |
| 1b duplicate | GW-AC-10 | I-10 |
| 3a risk deny `FR01` / `RR04` | ORC-AC-15, RSK-AC-03 | I-05, I-11 |
| 4a account refused | LED-AC-08, -09 | I-02, I-03, I-04 |
| 6b answer deadline | GW-AC-11, -12, -16, ORC-AC-16 | I-06, CH-04 |
| 8a CSM rejects after `ACCP` | ORC-AC-25 | I-07 |
| 8b no confirmation | ORC-AC-17 | I-08 |
| identity (`debtorAgentBic`, `TxId`) | GW-AC-15, -17, ORC-AC-26, SIM-AC-07 | I-12 |
| NFR: no dependency on VoP / notifications | NTF-AC-07 | I-13 |
| **UC-07** status mapping | API-AC-09 | every E2E (§2) |
| 1a not found / not owner | API-AC-05 | — |
| **UC-08** main, signature, `camt.054` | NTF-AC-01, -03 | O-01, I-01 (INV-13) |
| on-us: both parties | NTF-AC-02 | O-02 |
| 2a no subscription | NTF-AC-05 | — |
| 5a retries → `FAILED` | NTF-AC-04 | N-01, N-02 |
| rejected incoming not delivered (Q-19) | NTF-AC-09 | I-07 (INV-13) |
| at-least-once, dedup | NTF-AC-06 | CH-09 |
| **UC-09** 1–4 screening, provisional block | RSK-AC-05 | S-01 |
| 5–7 confirm → `AC06` | RSK-AC-06 | S-01 |
| 5a dismiss | RSK-AC-07 | S-01 |
| 1a provider down | RSK-AC-08 | S-02 |
| new account screened | RSK-AC-09 | S-03 |
| **UC-10** open, fund, read | LED-AC-01, -17 | A-01 |
| block / unblock, sanctions lift rights | LED-AC-13 | A-01, S-01 |
| close | LED-AC-14 | A-01 |
| 3a funding in `prod` | LED-AC-15 | — |
| NFR: admin actions audited | LED-AC-18 | — |

## 9. Supporting changes in other specs

The scenarios and invariants above rely on these criteria and features, added to the other specs together with
this strategy:

| Spec | Added | Used by |
|---|---|---|
| [00](00-engineering-baseline.md#13-local-development) | `systemtest` profile listed | §2.1 |
| [10](10-payment-api.md) | API-AC-06 extended: XML `FF01` `pain.002`, invalid IBAN on `/v1/payee-verifications` | UC-01 3a, UC-02 1a |
| [11](11-payment-orchestrator.md) | ORC-AC-21 … -26; CSM event dedup key includes `debtorAgentBic` (aligned with [02 §4](02-messaging-contracts.md)) | E2E-O-19, -O-20, -I-07, -I-12, CH-05 |
| [12](12-ledger-service.md) | LED-AC-18 (admin audit) | UC-10 NFR |
| [15](15-csm-gateway.md) | GW-AC-15, -16; command dedup key includes `debtorAgentBic` (aligned with [02 §3](02-messaging-contracts.md#3-csmoutboundcommands-orchestrator--gateway)) | E2E-I-12, I-06 |
| [16](16-notification-service.md) | NTF-AC-09 (Q-19) | INV-13 |
| [17](17-csm-simulator.md) | `LATE_STATUS:<ms>`, settlement log + `GET /sim/v1/settlements`, explicit `txId` / `debtorAgentBic` for incoming payments, outage modes `DOWN` / `RESET`; SIM-AC-05 … -08 | E2E-O-13, -O-20, -I-12, INV-10 |
| [30](30-delivery-plan.md) | every scenario of §4 / §5 assigned to a milestone or track exit; rule for scenarios that span tracks; contract-testing epic in M0 and the pacts of §10.1 in each exit | §7, §10 |
| [00](00-engineering-baseline.md) | Pact JVM in the stack; internal REST request bodies tolerate unknown fields; `pacts/released/`; CI steps for provider verification and the forward check | §10.3 |
| [README](README.md#definition-of-done-every-story) | Definition of Done: a new or changed integration call has a pact interaction | §10 |
| [30](30-delivery-plan.md) | M5: chaos / performance environment, CH-01 … CH-17, capacity test, alert-rule and probe tests; telemetry contract test in the M0 observability baseline | §1, §5, §6 |

Still open, at implementation time: the `application-systemtest.yml` of each service with the values of §2.1, and the
unit test of the ordering rule.

## 10. Consumer-driven contract testing (Pact)

Internal REST DTOs and Kafka records are shared Java records in `contracts`, so field names cannot drift within one
commit. Pact covers what shared records do not:

- the status codes and error `code`s a consumer branches on — the orchestrator's saga table
  ([11 §6.2](11-payment-orchestrator.md#62-pending-actions)) is built on ledger codes such as `HOLD_RELEASED` and
  `INSUFFICIENT_FUNDS`;
- behaviour behind a call: idempotent replay (`200` with the existing hold), tombstones, `409` conflicts;
- headers: `Idempotency-Key`, `application/problem+json`, Kafka `tpe-message-type` / `tpe-schema-version`
  ([01 §3.4](01-shared-libraries.md#34-headers));
- mixed versions during a rolling deploy (no maintenance window, [00 §9](00-engineering-baseline.md#9-database-and-migrations)):
  a consumer of release N+1 talking to a provider of release N, and the reverse.

### 10.1 Integration inventory

Pacticipant names are the Maven `artifactId`s, plus the logical provider `vop-responder` and the consumer
`system-tests`. Ids are used in test names (`PACT_P04_createHoldInsufficientFunds`).

| Id | Consumer → provider | Interface | Interactions (at least) |
|---|---|---|---|
| P-01 | payment-api → payment-orchestrator | `POST /v1/payments`, `GET /v1/payments/{id}` | submit carries `vopResult`; new payment `201`; existing `commandId` `200`; `409 COMMAND_CONFLICT`; `422 CREDITOR_AGENT_NOT_REACHABLE`; status in each internal state; `404 PAYMENT_NOT_FOUND` |
| P-02 | payment-api → vop-service | `POST /v1/verifications`, `POST /v1/verifications/{ref}/check` | `201` per result (`CLOSE_MATCH` with `actualName`); `422 PAYEE_BANK_UNKNOWN`; check `valid`, `EXPIRED`, `MISMATCH`, `UNKNOWN` |
| P-03 | payment-orchestrator → risk-service | `POST /v1/risk/assessments` | outgoing requests carry `vopResult`; `ALLOW`; `DENY FR01`; `DENY RR04`; `REVIEW`; replayed `assessmentId` → same decision; both directions |
| P-04 | payment-orchestrator → ledger-service | holds and pending credits ([12 §3.1](12-ledger-service.md)) | every success and every refusal the orchestrator maps: create hold `201` / replay `200` / `INSUFFICIENT_FUNDS` / `ACCOUNT_NOT_FOUND` / `ACCOUNT_BLOCKED` / `ACCOUNT_CLOSED` / `CUSTOMER_ID_MISSING` / `HOLD_RELEASED`; settle `200` / replay / `HOLD_RELEASED`; settle-on-us `200` / payee refusals; release `200` / unknown id (tombstone) / `409 HOLD_SETTLED`; pending credit create / confirm / cancel with the same pattern; `409 IDEMPOTENCY_CONFLICT` |
| P-05 | risk-service → ledger-service | `GET /v1/customers/{customerId}/accounts`, `POST /v1/accounts/{id}/block`, `/unblock` | list accounts; block `SANCTIONS_PROVISIONAL` and `SANCTIONS`; unblock; repeated block; unknown account |
| P-06 | csm-gateway → csm-simulator | `POST /csm/v1/messages` (XML envelope) | `202` for `pacs.008`, `pacs.002`, `pacs.028`; `400` for an unparsable envelope |
| P-07 | csm-simulator → csm-gateway | `POST /csm/v1/inbound` (XML envelope) | `202` for `pacs.008` and `pacs.002`; `202` for a schema-invalid `pacs.008` with readable `TxId` (answered later with `FF01`); `400` without a readable `TxId` |
| P-08 | vop-service → `vop-responder` | `POST /vop/v1/verifications` | `MATCH`, `CLOSE_MATCH`, `NO_MATCH`, `NOT_POSSIBLE` — verified against **both** csm-simulator and vop-service's own responder, so we can answer every request we send (UC-02 ↔ UC-03) |
| P-09 | risk-service → csm-simulator (sanctions provider) | `GET /sanctions/v1/lists/{listCode}/metadata`, `GET /sanctions/v1/lists/{listCode}` | current list; `503` outage |
| P-10 | system-tests → back-office APIs, simulator control | exception cases ([11 §3.2](11-payment-orchestrator.md#32-exception-case-api-role-backoffice_operator)), journal ([15 §3.2](15-csm-gateway.md#32-admin-api-role-backoffice_operator-read-only)), ledger account API, `/sim/v1` | the calls the test harness makes, so harness drift fails a PR instead of the nightly run |
| M-01 | payment-orchestrator → csm-gateway | Kafka `csm.outbound.commands` | `SendCreditTransfer`; `RequestPaymentStatus` (both directions); `AcceptCreditTransfer`; `RejectCreditTransfer` |
| M-02 | csm-gateway → payment-orchestrator | Kafka `csm.inbound.events` | `CreditTransferReceived`; `AnswerSent` (`ACCP`, `RJCT` by verdict, `RJCT AB05` by deadline); `CreditTransferSettled` / `CreditTransferRejected` per direction and `source`; `CreditTransferNotSent` (`AB03`, `FF01`) |
| M-03 | payment-orchestrator → notification-service | Kafka `payments.events` | `PaymentSettled` and `PaymentRejected` for outgoing, incoming and on-us |
| M-04 | ledger-service → vop-service, risk-service | Kafka `accounts.events` | `AccountOpened`, `AccountBlocked`, `AccountUnblocked`, `AccountClosed` snapshots. Tombstones (`null` value) cannot be expressed as a Pact message: VOP-AC-07 and the risk projection tests cover them |

Not covered by Pact:

| Integration | Why | Covered by |
|---|---|---|
| customer channels → payment-api, notification subscription API | consumers outside this repository | OpenAPI drift + openapi-diff breaking-change gate, security matrix, system tests. If a channel team publishes pacts, payment-api verifies them like any other |
| notification-service → customer webhook receivers | provider outside this repository | published webhook schema and signature rules ([16](16-notification-service.md)), validated by the system-test receiver stub (INV-13) |
| real CSM (TIPS / RT1), EPC VoP scheme, sanctions list providers | external parties, no Pact | ISO 20022 XSDs (ISO-AC-01, -02); P-06 … P-09 keep the simulator faithful to our clients; scheme conformance testing before production |

### 10.2 Writing consumer pacts

- Consumer tests live in the consumer module next to the adapter (`infrastructure.<provider>`, `src/test`), named
  `<Provider>PactTest`, and drive the **real client adapter** (its `RestClient` configuration and error mapping)
  against the Pact mock server. They assert on the adapter's result (e.g. `HoldResult.refused(INSUFFICIENT_FUNDS)`),
  so a pact cannot promise a response the code does not handle.
- Message consumer tests pass the pact message (body and metadata) through the real listener mapping and
  `ContractValidator` up to the application-service boundary, which is mocked.
- One interaction per response branch the consumer handles, named after the branch (`create hold: insufficient funds`).
- Consumers specify only the fields they read. Matchers: type for ids, timestamps and amounts; regex for IBAN, BIC,
  `TxId` and reason codes; exact values for enums, `status` and `code`. Requests include the headers the provider
  requires (`Idempotency-Key`); responses include `Content-Type`.
- XML bodies (P-06, P-07): the pact covers the envelope and the transport answer; the document content is the XSDs' job.
- Provider states use domain language with parameters: `"account is OPEN with available balance"` (`iban`,
  `availableMinor`), `"hold is RELEASED"` (`holdId`).
- Technical failures (timeouts, `5xx`, connection resets) are never in pacts; they stay in WireMock component tests.

### 10.3 Verifying pacts

- Provider tests live in `src/integrationTest` (`@Provider("<artifactId>")`): the real application on a random port
  with Testcontainers PostgreSQL (and Kafka for message providers); `@State` handlers seed data through application
  services. Message providers produce each message through the real outbox path and return the outbox row's payload
  and headers, so the verified bytes are the bytes the relay publishes.
- Consumer tests write pacts to `target/pacts` of their module; the build collects them in `<root>/target/pacts`.
  Services do not depend on each other, so the Maven reactor gives no order between consumer and provider; provider
  verification therefore runs as a separate CI step after the build (`-Ppact-verify`,
  [00 §14](00-engineering-baseline.md#14-continuous-integration)).
- Three checks:

| Check | Pacts from | Provider from | When | Protects |
|---|---|---|---|---|
| Same commit | this commit | this commit | every PR | normal development |
| Backward | last release, `pacts/released/<version>/` (committed by the release job) | this commit | every PR | consumers of the old release still running during a rolling deploy |
| Forward | this commit | last release tag (its provider tests, checked out) | nightly and before every release | new consumers that start before their providers have finished rolling |

- A breaking change follows expand / contract, as for migrations: the provider accepts old and new → release →
  consumers switch → release → the old form is removed. The backward and forward checks enforce that order. Example:
  a consumer adding an optional field to an internal request body is safe only because internal providers ignore
  unknown fields ([00 §2](00-engineering-baseline.md#2-technology-stack)).
- No Pact Broker in the MVP: all services are built and released together from one repository
  ([ADR-001](../architecture/decisions/ADR-001-microservices-maven-monorepo.md)), so pact files in the build and the
  released folder are enough. Revisit when services deploy independently: a self-hosted Pact Broker with
  `record-deployment` and `can-i-deploy` then replaces the released folder and the forward job.

### 10.4 Pacts and WireMock stubs

Component tests keep WireMock for faults and deadline-derived timeouts. Every WireMock mapping that returns a
business response must match an interaction of the consumer's own pact: `StubConformanceTest` (shared test fixture)
matches each mapping's request and response against the pact with Pact's matchers. Mappings for faults (delay beyond
the timeout, `5xx`, connection reset) carry `"metadata": { "pact": "fault" }` and are exempt. This keeps, for example,
the `INSUFFICIENT_FUNDS` stub of ORC-AC-05 identical to what ledger-service actually returns.

### 10.5 Example (consumer side, P-04)

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "ledger-service", pactVersion = PactSpecVersion.V4)
class LedgerServicePactTest {

  @Pact(consumer = "payment-orchestrator")
  V4Pact createHoldInsufficientFunds(PactDslWithProvider builder) {
    return builder
        .given("account is OPEN with available balance", Map.of("iban", IBAN, "availableMinor", 1000))
        .uponReceiving("create hold: insufficient funds")
          .method("POST").path("/v1/holds")
          .matchHeader("Idempotency-Key", "hold-create:[0-9a-f-]{36}")
          .body(newJsonBody(b -> {
              b.uuid("holdId");
              b.stringValue("debtorIban", IBAN);
              b.integerType("amountMinor", 12345);
              b.stringValue("currency", "EUR");
          }).build())
        .willRespondWith()
          .status(422)
          .matchHeader("Content-Type", "application/problem\\+json.*")
          .body(newJsonBody(b -> b.stringValue("code", "INSUFFICIENT_FUNDS")).build())
        .toPact(V4Pact.class);
  }

  @Test
  @PactTestFor(pactMethod = "createHoldInsufficientFunds")
  void PACT_P04_createHoldInsufficientFunds(MockServer server) {
    var result = ledgerClient(server).createHold(holdRequest(IBAN, 12_345));
    assertThat(result).isEqualTo(HoldResult.refused(LedgerRefusal.INSUFFICIENT_FUNDS));
  }
}
```

The provider side registers `@State("account is OPEN with available balance")`, which opens and funds the account
through the ledger's application services, and verifies all interactions with `PactVerificationSpringProvider`.
