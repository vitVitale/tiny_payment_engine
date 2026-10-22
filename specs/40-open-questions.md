# 40 Open questions and proposed decisions

These are decisions the specs had to make because the architecture is silent or ambiguous. The specs already follow
every proposal, so development can start. Each question is in one of these states:

- **Decided** — confirmed by the project owner (2026-10-21); the specs follow it.
- **Out of scope** — confirmed as not part of the current scope.
- **Decided — ADR** — written up as an architecture decision record with options and trade-offs, and accepted
  (2026-10-21).
- **Assumption** — a small decision the specs make; confirm it or change it here.
- **Needs input** — cannot be settled from the design; the named owner must answer before the milestone shown.

## Summary

| # | Topic | Decision in the specs | Status | Close by |
|---|---|---|---|---|
| Q-01 | Reason code for internal technical failures | `MS03`, added to the [glossary](../architecture/glossary.md) | Decided | M2 |
| Q-02 | Customer identity across accounts | `customerId` mandatory on every customer account; technical reject `CUSTOMER_ID_MISSING` → `MS03` | Decided | M1 |
| Q-03 | Outbox relay concurrency | one active relay per service | Decided — [ADR-006](../architecture/decisions/ADR-006-ordered-outbox-relay.md) | M0 |
| Q-04 | Saga resume mechanism | persisted pending action, lease, sweeper | Decided — [ADR-008](../architecture/decisions/ADR-008-resumable-saga-steps.md) | M2 |
| Q-05 | Simulated sanctions list provider | csm-simulator serves a JSON list | Assumption | sanctions track |
| Q-06 | Incoming settlement-overdue timer | 30 s after `AnswerSent(ACCP)` | Assumption | M4 |
| Q-07 | Pending credit id and `TxId` uniqueness | identity = (`debtorAgentBic`, `TxId`); pending credit id = `paymentId` | Decided — [ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md) | M1 |
| Q-08 | Hold and pending-credit tombstones | compensation of an unknown id leaves a tombstone | Decided — [ADR-009](../architecture/decisions/ADR-009-ledger-tombstones.md) | M1 |
| Q-09 | VoP personal-data retention | names kept 24 h after expiry; hashed audit 13 months | Decided | before production |
| Q-10 | Service-to-service and back-office authentication | OAuth2 client credentials + OIDC staff login, from M5 | Decided — [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md) | M5 |
| Q-11 | Which customer responses are stored for idempotency | only `202` | Assumption | M2 |
| Q-12 | Interbank settlement date | UTC calendar date | Assumption (revisit with UC-12) | M3 |
| Q-13 | Target load | 100 payments/s sustained, 300/s peak | Decided | M5 |
| Q-14 | Accounts topic | compacted, full snapshot + `revision` per event | Decided — [ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md) | M1 |
| Q-15 | On-us payments and the 20 s deadline | no deadline rejection after funds are reserved | Decided — [ADR-008](../architecture/decisions/ADR-008-resumable-saga-steps.md) | M2 |
| Q-16 | Holder data changes | not built; design sketch kept for later | Out of scope | — |
| Q-17 | Exception resolution controls | single operator in the MVP; four-eyes before production | Decided | before production |
| Q-18 | Package and group naming | `com.tpe` | Assumption | M0 |
| Q-19 | Notifications for rejected incoming payments | event emitted, not delivered | Assumption | notifications track |
| Q-20 | Gateway transmission rule | send-by checked before every attempt; never attempted ⇒ not sent | Decided — [ADR-011](../architecture/decisions/ADR-011-send-by-guard-per-transmission.md) | M3 |
| Q-21 | SLO and SLA figures | SLOs and SLAs of [25](25-service-levels.md) | SLOs: Assumption · SLAs: Needs input (product owner, compliance, payments operations) | M5 |
| Q-22 | Funds availability for incoming payments | booked on `CreditTransferSettled`; SLO measured from there | Needs input (compliance) | M4 |
| Q-23 | Synthetic probes in production | read-only probes; 0.01 EUR on-us probe only if approved | Needs input (compliance, accounting) | M5 |
| Q-24 | Service token lifetime and IdP availability | refresh at 50 % of lifetime, alert on refresh failure; lifetime open | Needs input (security) | M5 |
| Q-25 | Back-office and compliance staffing | exception cases acknowledged ≤ 1 h in staffed hours, resolved ≤ 1 business day | Needs input (payments operations) | before production |
| Q-26 | Telemetry hosting | self-hosted Grafana stack (Mimir, Loki, Tempo) on in-house object storage | Needs input (security, procurement) | M5 |
| Q-27 | Telemetry retention and personal data | metrics 13 months, logs 30 d, traces 14 d; payment identifiers in logs and traces, no names / IBANs | Needs input (data-protection officer) | M5 |
| Q-28 | Paging and ticketing tools | Alertmanager routes `page` to a paging tool, `ticket` to the issue tracker; tools not chosen | Needs input (payments operations) | M5 |
| Q-29 | Telemetry stack and payment trace model | [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md), [ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md) | Assumption — ADRs proposed | M0 |

## Details

### Q-01 Reason code for internal technical failures

**Context.** Some rejections have no business reason: the ledger was unreachable until shortly before the send-by
time, or the pending credit could not be created before the answer deadline. The glossary lists no code for that.

**Options.** `MS03` "reason not specified" · `AB05` / `AB06` timeouts · `NARR` with a free-text narrative.

**Proposal.** `MS03`. Timeout codes would mislead investigations: the cause is internal, not a timeout at another
agent. `NARR` needs a narrative, which the scheme does not need from us. `MS03` is used on outgoing `pain.002` to our
own customer, and on incoming rejections (`pacs.002 RJCT`) sent to the CSM.

**Decision.** Keep `MS03`. Verification against the current EPC SCT Inst implementation guidelines' reject reason code
list is part of the scheme conformance checks in M3 / M4.

### Q-02 Customer identity across accounts

**Context.** Sanctions screening (UC-09) must block *all* accounts of a listed person, and run statistics count
customers, not accounts. The architecture has holder data per account only.

**Options.** (a) An explicit `customerId` supplied when an account is opened. (b) Grouping accounts by matching holder
data (name + date of birth). (c) A separate customer service.

**Proposal.** (a). Matching holder data is unreliable (typos, name changes) and would leave some accounts of a listed
person unblocked. A customer service is out of scope for this engine; banks already have a customer number in their
core system.

**Decision.** `customerId` is mandatory and is expected to be supplied every time. As a safety net, a missing one is a
technical reject, never a silent default:

- Opening an account without it → `422 CUSTOMER_ID_MISSING` ([12 §3.2](12-ledger-service.md#32-account-api)); format
  1–50 chars `[A-Za-z0-9_-]`.
- The ledger DB enforces it with a `CHECK` constraint, and the payment operations check it again. A customer account
  without a customer number cannot take a hold or a pending credit: the ledger answers `CUSTOMER_ID_MISSING`, and the
  orchestrator rejects the payment with the technical reason `MS03` ([11 §6.2](11-payment-orchestrator.md#62-pending-actions)).
- An `accounts.events` message without `customerId` fails contract validation and goes to the DLT, which raises an alert.

### Q-03 Outbox relay concurrency

See [ADR-006](../architecture/decisions/ADR-006-ordered-outbox-relay.md). Summary: two relay instances publishing at the
same time can reorder messages of one payment, and ADR-004 needs that order. One relay per service (PostgreSQL
advisory lock) handles about 4–8× the expected peak; sharding by key is the upgrade path. The ADR also adds a short
visibility window, because `BIGSERIAL` ids can commit out of order.

### Q-04 Saga resume mechanism

See [ADR-008](../architecture/decisions/ADR-008-resumable-saga-steps.md). Summary: the next remote step is stored on the
payment row and claimed with a lease. Remote calls happen outside transactions, and results are applied only if the
payment has not moved on. Crash recovery and normal processing are the same code path.

### Q-05 Simulated sanctions list provider

**Context.** The container view marks the sanctions provider as simulated but does not say who simulates it.

**Proposal.** csm-simulator serves a simple JSON list ([17 §3.4](17-csm-simulator.md#34-sanctions-list-provider)). It
already hosts all other external parties and has a control API for test scenarios. risk-service reads the list
through a format adapter, so a real EU Financial Sanctions Files (XML) adapter can be added later without changing the
screening logic.

### Q-06 Incoming settlement-overdue timer

**Context.** After we send `ACCP`, the CSM settles and sends `ACSC`. UC-06 8b needs a timer for the case where the
confirmation never comes, but gives no value.

**Reasoning.** The Originator PSP's time-out deadline is 20 s after its own acceptance, and the CSM rejects late
transactions after that point. By the time we answer, a few seconds of that budget are already used. A confirmation
that has not arrived 30 s after our answer is therefore overdue in every normal case, and investigating earlier
would mostly create `pacs.028` traffic for confirmations that are merely slow.

**Proposal.** 30 s (`tpe.incoming.settlement-overdue`), retuned in M5 with measured simulator and Kafka latency.

### Q-07 Pending credit id and incoming TxId uniqueness

See [ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md). Summary: `TxId` is unique only per
Originator PSP, so two banks can send the same value. Identity becomes (`debtorAgentBic`, `TxId`) in the gateway,
the orchestrator and the contracts, and the pending credit id becomes the incoming `paymentId`. This changes ADR-005,
and the specs are already updated.

### Q-08 Hold and pending-credit tombstones

See [ADR-009](../architecture/decisions/ADR-009-ledger-tombstones.md). Summary: a release or cancel can reach the ledger
before the create it compensates. Recording a tombstone makes the late create fail, so the orchestrator can always
compensate without knowing whether the create happened.

### Q-09 VoP personal-data retention

**Context.** Payee names in VoP requests and results are personal data (UC-02 NFR: "stored only as long as needed").
Two different stores exist: results we requested (needed until the payment is initiated) and requests we answered as
responding PSP (needed as evidence of what we answered).

**Proposal (defaults in [13 §6.3](13-vop-service.md#63-retention)).**

| Data | Kept in clear | Then |
|---|---|---|
| Our VoP results (payee name, actual name) | reference validity (30 min) + 24 h | names set to `NULL`; row deleted after 30 days |
| Responder audit | never in clear | peppered SHA-256 of IBAN and name, kept 13 months |
| VoP reference on the payment | as long as the payment | reference only, no names |

**Decision.** The periods above are confirmed.

### Q-10 Service-to-service and back-office authentication

See [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md). Summary: services use OAuth2
client-credentials tokens with a scope per capability (for example, only risk-service may lift sanctions blocks); staff
log in with OIDC and roles; network isolation remains a second layer. It is switched on in M5 and mandatory in `prod`.

### Q-11 Which customer responses are stored for idempotency

**Context.** UC-01 defines replay for a successful submission and `422` for a reused key with a different payload. It
does not say whether rejected requests are stored.

**Options.** Store every final response (Stripe-style) · store only successful submissions.

**Proposal.** Store only `202`. Validation failures have no side effect and are deterministic, so replaying them adds
nothing. Not storing them lets a client fix a payload (for example, after fetching a new VoP reference) and retry with
the same key, instead of hitting `IDEMPOTENCY_KEY_REUSED`.

### Q-12 Interbank settlement date

**Context.** `pacs.008` needs `IntrBkSttlmDt`. SCT Inst runs 24/7, but the CSM still has a business day: for TIPS it
follows the TARGET business day, which changes in the evening, not at midnight.

**Proposal.** In the MVP the simulator accepts the UTC calendar date. When reconciliation (UC-12) is built, the
gateway takes the current business date from the CSM (or a configured calendar), because `camt.053` statements are
per business day.

### Q-13 Target load

**Context.** The architecture states latency targets but no volume. Volume sizes the outbox relay (ADR-006), the
Kafka partitions and the performance tests.

**Assumed model.** A bank with 1 M customers making on average 2 instant payments a day sends and receives about
2 M payments a day. If 10 % of a day's payments fall in the busiest hour, that hour carries about 200 000
payments, or 55/s; short bursts at 3× that give about 170/s. **100/s sustained and 300/s peak** cover this with headroom.

**Decision.** 100/s sustained, 300/s peak are confirmed as the targets. If real volumes later exceed about 1 000/s at
peak, revisit ADR-006 (relay sharding) and the partition counts in [02 §2](02-messaging-contracts.md#2-topics).

### Q-14 Accounts topic

See [ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md). Summary: `accounts.events` is
compacted and every event carries the full account snapshot and a `revision`, so vop and risk projections can be
rebuilt from the topic at any time. Closed accounts get a Kafka tombstone after 13 months, so personal data does not
stay on the topic forever.

### Q-15 On-us payments and the 20 s deadline

See [ADR-008](../architecture/decisions/ADR-008-resumable-saga-steps.md), last decision bullet. Summary: rejecting an on-us
payment at the deadline while `settle-on-us` is in flight could leave the payer debited and the payment `REJECTED`.
Once funds are reserved, on-us payments finish on a definitive ledger answer instead.

### Q-16 Holder data changes

**Context.** Names and addresses change (marriage, company rename, move abroad). VoP answers and sanctions screening
both use holder data, so stale data gives wrong VoP results and missed or false sanctions hits. The MVP has no way to
change holder data.

**Decision.** Out of scope for now. Holder data is set when an account is opened and does not change. The sketch below
is kept for when it is picked up.

**Design sketch (not planned).**

- ledger-service: `PUT /v1/accounts/{id}/holder` (role `BACKOFFICE_OPERATOR`, audited) — or per customer, if Q-02 is
  confirmed, `PUT /v1/customers/{customerId}/holder`, updating all accounts.
- `revision + 1` and an `AccountHolderUpdated` event with the full snapshot; no consumer change is needed
  ([ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md)).
- risk-service screens that customer again against the current list as soon as the event arrives, as it does for
  `AccountOpened`.
- Outstanding VoP references stay valid for their 30 minutes; they were issued for the name the payer entered, not for
  the stored name.

**Note.** While this is out of scope, a holder whose name changes needs a new account. Stale names make VoP answers
and sanctions screening less accurate, so pick this up before production use with real customers.

### Q-17 Exception resolution controls

**Context.** Resolving an exception case as `CONFIRMED_SETTLED` or `CONFIRMED_REJECTED` moves money, and dismissing a
sanctions case lifts a block. In the MVP one operator can do either alone.

**Proposal for production.** A maker-checker (four-eyes) rule for those three actions: the first user proposes, a
second user with the same role approves. Both are recorded. This needs a `PROPOSED` case status and an approval
endpoint in the orchestrator and risk-service, and it builds on the staff roles of ADR-012.

**Decision.** Confirmed: one operator in the MVP; the maker-checker rule above for those three actions before
production, with the second user holding the same role.

### Q-18 Package and group naming

`com.tpe` for the Maven `groupId` and the Java root package. If the project gets a real domain, change it in M0,
before any code depends on it.

### Q-19 Notifications for rejected incoming payments

A payee is told about an incoming payment only when it is credited. `PaymentRejected` with `direction = INCOMING` is
still emitted (so `payments.events` is complete for every consumer), but notification-service only records it and
does not deliver it. A payee who was never credited has nothing to act on, and a notification would only cause
support calls.

### Q-20 Gateway transmission rule

See [ADR-011](../architecture/decisions/ADR-011-send-by-guard-per-transmission.md). Summary: the send-by check runs before
every transmission attempt, not only when the command is consumed. A transfer journalled as *sent* but never
attempted (gateway crash) becomes *not sent* as soon as the send-by time passes, without waiting for an investigation.

### Q-21 SLO and SLA figures

**Context.** The architecture and the use cases give latency targets but no availability targets, and nothing says
what the engine promises to its channels, the scheme or compliance. [25](25-service-levels.md) derives both from the
scheme timings, the use-case NFRs and the target load (Q-13).

**Proposal.** The SLOs of [25 §5](25-service-levels.md#5-component-slos) (tier 0 at 99.99 % per component, 99.95 % for
the customer journeys) and the SLAs of [25 §4](25-service-levels.md#4-slas), each looser than its SLO.

**Decision needed.** SLOs: confirm, then recalibrate once with the M5 performance runs. SLAs: each party confirms its
row (channel teams SLA-01 … -05, payments operations for the scheme SLA-06 / -07, compliance SLA-08).

### Q-22 Funds availability for incoming payments

**Context.** The ledger books an incoming payment when the CSM reports settlement (`CONFIRM_CREDIT` after
`CreditTransferSettled`); until then it is a pending credit and not available to the payee
([ADR-005](../architecture/decisions/ADR-005-ledger-booking-model.md)). SLO-ORC-05 therefore measures funds
availability from the settlement report.

**Decision needed.** Compliance confirms against the current SCT Inst rulebook and Regulation (EU) 2024/886 whether
funds must be available from the settlement report (current design) or already from our positive confirmation. If
the latter, the booking model and CUJ-4 change, not only the SLO.

### Q-23 Synthetic probes in production

**Context.** At low traffic, burn-rate alerts have too few events, and server-side metrics do not see ingress
failures ([25 §9.3](25-service-levels.md#93-low-traffic-and-synthetic-probes)).

**Proposal.** Read-only probes (own-bank VoP verification, status of a fixed payment) every 30 s from outside the
cluster. An on-us payment of 0.01 EUR every minute between two probe customer accounts would test the whole outgoing
path, but it creates real postings and needs probe customers with a `customerId`.

**Decision needed.** Compliance and accounting approve or refuse the payment probe; read-only probes need no approval.

### Q-24 Service token lifetime and IdP availability

**Context.** [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md) gives service tokens a
5 min lifetime. Once internal authentication is mandatory, an IdP outage longer than the time between refresh and
expiry stops every internal call, so the IdP becomes a tier-0 dependency ([25 §13](25-service-levels.md#13-risks-and-what-to-revisit) R-3).

**Options.** (a) Keep 5 min, refresh at 50 %, IdP SLO 99.99 %. (b) 30 min lifetime, refresh at 50 % — rides through a
15 min IdP outage at the cost of a longer window for a leaked token. (c) mTLS for service identity, tokens only for
staff.

**Decision needed.** Security chooses; amend ADR-012.

### Q-25 Back-office and compliance staffing

**Context.** Exception cases (orchestrator) hold customer money or contradict the CSM; screening cases keep a
customer provisionally frozen. Their resolution time is a human service level (OLA-ORC-01 in
[25 §5.2](25-service-levels.md#52-payment-orchestrator)).

**Decision needed.** Staffed hours of back office and compliance, and whether OLA-ORC-01 (acknowledge ≤ 1 h, resolve
≤ 1 business day) is achievable. Cases that need engineering (`LEDGER_ACTION_STUCK`, `DLT_MESSAGE` on `csm.*`) page
on-call 24/7 regardless.

### Q-26 Telemetry hosting

**Context.** [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md) chooses the Grafana stack.
It can run self-hosted or as Grafana Cloud. Telemetry carries pseudonymous payment identifiers (`paymentId`, `txId`,
`uetr`), so a SaaS backend is an ICT outsourcing arrangement under DORA, with data-residency and exit requirements.

**Proposal.** Self-hosted (Mimir, Loki, Tempo, Grafana on in-house object storage, [26 §7](26-observability.md#7-backends)).
The Alloy configuration differs from Grafana Cloud only in endpoints and credentials, so the choice can be revisited.

**Decision needed.** Security and procurement confirm self-hosting, or approve Grafana Cloud (region, contract).

### Q-27 Telemetry retention and personal data

**Context.** Names, IBANs and remittance text never reach telemetry ([00 §11](00-engineering-baseline.md#11-logging-tracing-metrics),
[26 §4.4, §6.2](26-observability.md)). Logs and traces do carry `paymentId`, `txId` and `uetr`, which the payments
database links to a person.

**Proposal.** Metrics 13 months (monthly SLO reports, year-on-year), logs 30 d, traces 14 d; Loki access restricted to
on-call ([26 §7](26-observability.md#7-backends)). Telemetry is never the record used for audits or disputes.

**Decision needed.** The data-protection officer confirms the identifiers may be kept for these periods and who may
read them.

### Q-28 Paging and ticketing tools

**Context.** Alertmanager needs receivers for pages (24/7, tier 0 and 1), tickets, chat and the Watchdog heartbeat
([26 §8.4](26-observability.md#84-routing)).

**Decision needed.** Payments operations name the paging tool and escalation policies per team, the issue tracker
for tickets, and the external heartbeat service.

### Q-29 Telemetry stack and payment trace model

**Context.** The architecture README listed the production telemetry stack and per-payment tracing as open.

**Proposal.** [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md) (Micrometer + OTel, Alloy,
Mimir / Loki / Tempo, rules in the Mimir ruler) and [ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md)
(one trace per payment lifecycle, parent / child across `csm.*`, 100 % sampling). The specs follow both.

**Decision needed.** Accept both ADRs before the M0 observability baseline is built.
