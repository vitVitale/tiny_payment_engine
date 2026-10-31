# 30 Delivery plan

Milestones are vertical slices: each ends with runnable end-to-end scenarios from
[20 §4](20-testing-strategy.md#4-end-to-end-scenario-catalogue), not with "service X done". Sizes are rough
(S ≤ 2 days, M ≤ 1 week, L ≤ 2 weeks for one developer).

## Dependency overview

```
M0 Foundations ──► M1 Ledger & accounts ──► M2 On-us payment ──► M3 Outgoing via CSM ──► M5 Hardening
                                    │                        └──► M4 Incoming via CSM ──┘
                                    └──► (parallel) VoP remote, sanctions, notifications (M2–M4 tracks)
```

After M1 four tracks can run in parallel: **payments core** (orchestrator + payment-api), **CSM** (gateway +
simulator), **VoP**, **risk / sanctions**. Notifications can start once `payments.events` is in `contracts` (M0).

**Scenarios that span tracks.** A milestone's exit scenarios are green without the parts another track delivers:
webhook assertions (INV-13 of [20 §3](20-testing-strategy.md#3-global-invariants)) stay pending until the
notifications track exit, then apply to every scenario already green. Scenarios that need non-EU list import
(E2E-O-06, E2E-I-11) are exit criteria of the sanctions track, not of M2 / M4.

## M0 — Foundations

| Epic | Stories | Size | Spec |
|---|---|---|---|
| Repo & build | parent POM, Maven wrapper, module skeletons for all services / libs, Enforcer + ArchUnit rules, CI pipeline (build, test, plantuml check, AC traceability check of [20 §1](20-testing-strategy.md#1-test-levels)) | M | [00 §1, §3, §14](00-engineering-baseline.md) |
| Local stack | `deploy/docker-compose.yml` (PostgreSQL with per-service DBs, Kafka KRaft + topic init, mock OAuth2), `local` profiles | S | [00 §13](00-engineering-baseline.md#13-local-development) |
| `payment-domain` | value objects, reason codes, ids, `MutableClock`, `BankDirectory` | M | [01 §1](01-shared-libraries.md#1-payment-domain) |
| `messaging` | outbox writer + single-leader relay, inbox, error handler + DLT, `PollingWorker`, metrics; MSG-AC-01…08 | L | [01 §3](01-shared-libraries.md#3-messaging) |
| `contracts` | all records of [02](02-messaging-contracts.md) + golden samples; internal REST DTOs added per milestone | M | [01 §4](01-shared-libraries.md#4-contracts) |
| `iso20022-model` | XSDs, JAXB generation, codec, envelope, XXE-safe validator; builders added in M3 / M4 | M | [01 §2](01-shared-libraries.md#2-iso20022-model) |
| Observability baseline | `libs/observability`: structured logs, MDC, OTel, actuator endpoints, SLO histogram boundaries, `PaymentTelemetry`, trace context codec, telemetry contract test fixture ([20 §1](20-testing-strategy.md#1-test-levels)); `messaging` relay / consumer spans with the per-topic parent rule; compose `observability` profile (Alloy, Mimir, Loki, Tempo, Grafana); OBS-AC-01 … -04 | M | [00 §11](00-engineering-baseline.md#11-logging-tracing-metrics), [26 §4, §6, §13](26-observability.md) |
| Contract testing | Pact JVM in the parent POM, pact collection into `<root>/target/pacts`, `-Ppact-verify` CI step, `StubConformanceTest` fixture, `pacts/released/` and the forward check in the release job | M | [20 §10](20-testing-strategy.md#10-consumer-driven-contract-testing-pact) |

Exit: `./mvnw verify` green with the four libraries; compose stack starts; a sample service publishes and consumes
through outbox / inbox in an integration test.

## M1 — Ledger and accounts (UC-10)

| Epic | Stories | Size |
|---|---|---|
| Accounts | open (IBAN generation), read, entries, block / unblock with reason rules, close, funding, audit; `accounts.events` | M |
| Posting engine | journal entries, balance trigger, optimistic locking, technical accounts + snapshots | M |
| Memos | holds (create / settle / settle-on-us / release, tombstones), pending credits (create / confirm / cancel) | M |
| Idempotency guard | key store + replay / conflict | S |

Exit: LED-AC-01 … -18 green; E2E-A-01 (projections stubbed by a test consumer).

## M2 — On-us payment (UC-01, UC-02 local, UC-03, UC-04 on-us path, UC-07)

| Epic | Stories | Size | Track |
|---|---|---|---|
| payment-api | security, JSON intake, validation, idempotency guard, status + `pain.002`, VoP proxy | L | payments core |
| payment-api XML | `pain.001` XML intake, `FF01` rejection document | M | payments core |
| orchestrator core | payments table, state machine, `SubmitPayment`, saga runner, sweeper, outgoing deadline timer | L | payments core |
| orchestrator on-us | `ASSESS_RISK`, `CREATE_HOLD`, `ASSESS_PAYEE_RISK`, `SETTLE_ON_US`, `RELEASE_HOLD`, `payments.events` | M | payments core |
| risk assessment | assessment API, rule engine with MVP rules (non-EU screening may return `ALLOW` until M4 track delivers lists) | M | risk |
| vop local | projection, responder, matcher, reference store, check endpoint, retention | M | VoP |

Exit: E2E-O-02, -03, -04, -05, -15, -16, -17, -18, -19 green; pacts P-01, P-02, P-03, P-04 (create / settle-on-us /
release hold), P-10 (calls used so far) and M-04 (vop-service) verified ([20 §10.1](20-testing-strategy.md#101-integration-inventory)).

## M3 — Outgoing via CSM (UC-04, UC-05)

| Epic | Stories | Size | Track |
|---|---|---|---|
| csm-simulator v1 | transport, outgoing scheme behaviour, scenarios `ACCEPT`/`REJECT`/`TIMEOUT`/`SILENT`/`LOST`/`LATE_STATUS`, investigation, outage modes, settlement log, control API | L | CSM |
| gateway outgoing | send-by guard, journal, transmitter, `pacs.008` builder, correlation of `pacs.002`, `RequestPaymentStatus` / `pacs.028` | L | CSM |
| orchestrator CSM path | `SEND`, CSM event listener (outgoing), `SETTLE_HOLD`, investigation timers, exception cases + API | L | payments core |

Exit: E2E-O-01, -07 … -14, -20, -21 green; CH-02, CH-03 green; pacts P-04 (settle hold), P-06, M-01 (send, status
request) and M-02 (outgoing events) verified; OBS-AC-01 with a real outgoing payment instead of the M0 sample workload.

## M4 — Incoming via CSM (UC-06) — can run in parallel with M3 once the simulator transport exists

| Epic | Stories | Size | Track |
|---|---|---|---|
| simulator incoming | incoming-payment control API (incl. explicit `txId` / `debtorAgentBic`), answer handling, `REJECT_AFTER_ACCP`, `NO_CONFIRMATION`, raw XML | M | CSM |
| gateway incoming | `pacs.008` validation / dedup, answer coordinator, deadline worker, settlement correlation | L | CSM |
| orchestrator incoming saga | `CreditTransferReceived`, incoming actions, `AnswerSent` handling, settlement-overdue timer | L | payments core |

Exit: E2E-I-01 … -10, -12, -13 green; GW-AC-12 race test; CH-04 green; pacts P-04 (pending credits), P-07, M-01
(verdicts) and M-02 (incoming events) verified; OBS-AC-01 with a real incoming payment.

## Parallel tracks (start after M1)

| Track | Epics | Exit |
|---|---|---|
| VoP remote | counterparty client, routing, simulator VoP endpoints | E2E-V-01 … -03; pact P-08 against simulator and own responder |
| Sanctions | list import (simulator provider), screening job, matcher, cases + API, ledger blocks, non-EU per-payment rule | E2E-O-06, E2E-I-11, E2E-S-01 … -03; RSK-AC-05 … -10; pacts P-05, P-09, M-04 (risk-service) |
| Notifications | subscriptions, listener, builder (JSON, `camt.054`), dispatcher, retries, admin API | E2E-N-01, -02; NTF-AC-01 … -09; pact M-03; INV-13 switched on for all green scenarios |

## M5 — Hardening

| Epic | Stories |
|---|---|
| Failure injection | chaos / performance environment of [20 §5](20-testing-strategy.md#5-failure-injection-scenarios) (synchronous-standby PostgreSQL for tier-0 databases, 3 Kafka brokers, two instances per tier-0 service, Prometheus + Alertmanager stub), Toxiproxy wiring, CH-01 … CH-17 nightly with budget report and page assertions (CH-15 from the first release on, CH-16 from M5) |
| Test gates | schema-compatibility job against the previous release, PIT mutation gate, nightly soak with invariant sweeps, negative tests of the invariant checks, alert-rule tests, synthetic-probe tests, configuration-consistency test, weekly capacity test ([20 §1, §3, §6, §7](20-testing-strategy.md)) |
| Performance | Gatling profile of [20 §6](20-testing-strategy.md#6-performance), tuning of `sendBy` / answer deadline from measured latency (ADR-002 / ADR-004 consequence) |
| Operations | SLI metrics of [25 §11](25-service-levels.md#11-metrics-to-add), SLO definition files and generated burn-rate rules ([25 §9](25-service-levels.md#9-alerting)), dashboards (budget remaining per SLO, payment latency vs 10 s budget, consumer lag, DLT, open cases, timer lag), production invariant checks ([25 §6](25-service-levels.md#6-correctness-invariants-zero-budget)), error-budget policy agreed ([25 §10](25-service-levels.md#10-error-budget-policy)) |
| Observability stack | Mimir, Loki, Tempo, Grafana, Alertmanager on object storage for `perf`, staging and production; Alloy agent / gateway with redaction; `tools/slo-rules`, `tools/consumer-lag-exporter`, `tools/invariant-checker`; dashboards and runbooks of [26 §8, §9](26-observability.md); k6 probes; Watchdog heartbeat; OBS-AC-05 … -13 (trace contract from M3 / M4 as the CSM paths land) |
| Internal authentication | [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md) action items: clients and scopes in the mock issuer (including `tpe-probe`, `tpe-invariant-checker`); shared Spring Security configuration for internal resource servers and the token interceptor (refresh at 50 %, refresh-failure alert); required scope or role declared on every internal controller; NetworkPolicy manifests; `tpe.security.internal-auth.enabled` on in `systemtest` and `prod`; authorisation matrix extended to internal endpoints ([20 §1](20-testing-strategy.md#1-test-levels)); CH-16 (15 min IdP outage, 30 min tokens) |
| Security hardening | requirements SEC-01 … SEC-09 of [00 §12.3](00-engineering-baseline.md#123-production-requirements) as decided in [ADR-015 … ADR-018](../architecture/decisions/): Kafka principals and ACLs (also in `systemtest`), TLS inside the cluster, secret store and rotation runbooks, rate limits, append-only audit grants and read audit, security events, encryption at rest, CI dependency and image scans and SBOM, STRIDE threat model |
| Cross-cutting gaps | decisions for the remaining open items of the architecture README (HA, liquidity) — ADRs before code |

Exit: full catalogue + chaos + performance targets green on `main` for 5 consecutive nightly runs.

## Later (not planned in detail)

UC-11 recall (`camt.056` / `camt.029` / `pacs.004`), UC-12 reconciliation (`camt.053`), CSM liquidity, real EU
sanctions XML adapter, EPC VoP API conformance.
