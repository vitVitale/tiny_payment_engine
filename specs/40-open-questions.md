# 40 Open questions and proposed decisions

These are decisions the specs had to make because the architecture is silent or ambiguous. The specs already follow
every proposal, so development can start. Each question is in one of these states:

- **Decided** — confirmed by the project owner (2026-10-21); the specs follow it.
- **Out of scope** — confirmed as not part of the current scope.
- **Decided — ADR** — written up as an architecture decision record with options and trade-offs, and accepted
  (2026-10-21 or 2026-10-22).
- **Decided — pending sign-off** — decided on 2026-10-22 and followed by the specs; the named owner confirms it
  before the milestone shown, or reopens it here.
- **Assumption** — a small decision the specs make; confirm it or change it here.
- **Needs input** — cannot be settled from the design; the named owner must answer before the milestone shown.

## Summary

| # | Topic | Decision in the specs | Status | Close by |
|---|---|---|---|---|
| Q-01 | Reason code for internal technical failures | `MS03`, added to the [glossary](../architecture/glossary.md) | Decided | M2 |
| Q-02 | Customer identity across accounts | `customerId` mandatory on every customer account; technical reject `CUSTOMER_ID_MISSING` → `MS03` | Decided | M1 |
| Q-03 | Outbox relay concurrency | one active relay per service | Decided — [ADR-006](../architecture/decisions/ADR-006-ordered-outbox-relay.md) | M0 |
| Q-04 | Saga resume mechanism | persisted pending action, lease, sweeper | Decided — [ADR-008](../architecture/decisions/ADR-008-resumable-saga-steps.md) | M2 |
| Q-05 | Simulated sanctions list provider | csm-simulator serves a JSON list | Decided | sanctions track |
| Q-06 | Incoming settlement-overdue timer | 30 s after `AnswerSent(ACCP)`, retuned with M5 measurements | Decided | M4 |
| Q-07 | Pending credit id and `TxId` uniqueness | identity = (`debtorAgentBic`, `TxId`); pending credit id = `paymentId` | Decided — [ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md) | M1 |
| Q-08 | Hold and pending-credit tombstones | compensation of an unknown id leaves a tombstone | Decided — [ADR-009](../architecture/decisions/ADR-009-ledger-tombstones.md) | M1 |
| Q-09 | VoP personal-data retention | names kept 24 h after expiry; hashed audit 13 months | Decided | before production |
| Q-10 | Service-to-service and back-office authentication | OAuth2 client credentials + OIDC staff login, from M5 | Decided — [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md) | M5 |
| Q-11 | Which customer responses are stored for idempotency | only `202` | Decided | M2 |
| Q-12 | Interbank settlement date | UTC calendar date in the MVP; CSM business date with UC-12 | Decided (revisit with UC-12) | M3 |
| Q-13 | Target load | 100 payments/s sustained, 300/s peak | Decided | M5 |
| Q-14 | Accounts topic | compacted, full snapshot + `revision` per event | Decided — [ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md) | M1 |
| Q-15 | On-us payments and the 20 s deadline | no deadline rejection after funds are reserved | Decided — [ADR-008](../architecture/decisions/ADR-008-resumable-saga-steps.md) | M2 |
| Q-16 | Holder data changes | not built; design sketch kept for later | Out of scope | — |
| Q-17 | Exception resolution controls | single operator in the MVP; four-eyes before production | Decided | before production |
| Q-18 | Package and group naming | `com.tpe` | Decided | M0 |
| Q-19 | Notifications for rejected incoming payments | event emitted, not delivered | Decided | notifications track |
| Q-20 | Gateway transmission rule | send-by checked before every attempt; never attempted ⇒ not sent | Decided — [ADR-011](../architecture/decisions/ADR-011-send-by-guard-per-transmission.md) | M3 |
| Q-21 | SLO and SLA figures | SLOs and SLAs of [25](25-service-levels.md) | SLOs: Decided (recalibrated once after M5) · SLAs: Decided — pending sign-off (channel teams, payments operations, compliance) | M5 |
| Q-22 | Funds availability for incoming payments | booked on `CreditTransferSettled`; ≤ 10 s from receipt tracked in CUJ-4 | Decided — pending sign-off (compliance) | M4 |
| Q-23 | Synthetic probes in production | read-only probes in production; the 0.01 EUR on-us probe only in `perf` and staging | Decided | M5 |
| Q-24 | Service token lifetime and IdP availability | 30 min lifetime, refresh at 50 %; IdP stays at 99.9 %; [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md) amended | Decided — pending sign-off (security) | M5 |
| Q-25 | Back-office and compliance staffing | staffed 7 days 08:00–20:00 CET; acknowledge ≤ 1 h, resolve ≤ 1 staffed day | Decided — pending sign-off (payments operations, compliance) | before production |
| Q-26 | Telemetry hosting | self-hosted Grafana stack (Mimir, Loki, Tempo) on in-house object storage | Decided — [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md) | M5 |
| Q-27 | Telemetry retention and personal data | metrics 13 months, logs 30 d, traces 14 d; payment identifiers in logs and traces, no names / IBANs | Decided — pending sign-off (data-protection officer) | M5 |
| Q-28 | Paging and ticketing tools | the bank's existing paging and ticketing tools through Alertmanager's native receivers | Decided (integration) · Needs input (tool names: payments operations) | M5 |
| Q-29 | Telemetry stack and payment trace model | [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md), [ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md) | Decided — ADR | M0 |
| Q-30 | Kafka authentication and authorisation (SEC-01) | SASL/SCRAM over TLS, one principal per service, ACLs from `deploy/kafka/topics.yml` | Decided — [ADR-015](../architecture/decisions/ADR-015-kafka-authentication-and-acls.md) · pending sign-off (security, platform) | M5 |
| Q-31 | TLS inside the cluster (SEC-02) | server-side TLS from an internal CA (cert-manager); identity stays with the JWT | Decided — [ADR-016](../architecture/decisions/ADR-016-tls-inside-the-cluster.md) · pending sign-off (security, platform) | M5 |
| Q-32 | Secret store and rotation (SEC-03) | Vault + External Secrets as mounted files; keyrings; rotation per secret | Decided — [ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md) · pending sign-off (security, platform) | M5 |
| Q-33 | Rate limits (SEC-04) | local token buckets in payment-api and vop-service; `429 RATE_LIMITED` | Decided — [ADR-018](../architecture/decisions/ADR-018-rate-limiting.md) · figures pending sign-off (channel teams, fraud prevention) | M5 |
| Q-34 | Customer authentication and the `accounts` claim (SEC-05) | SCA with dynamic linking at the channel; `accounts` from entitlement data; access tokens ≤ 5 min | Decided — pending sign-off (channel teams, security) | M2 |
| Q-35 | Audit retention and security events (SEC-06) | audit tables 10 years, read audit 2 years; security events to the bank's SIEM | Decided — pending sign-off (compliance, data-protection officer, security operations) | before production |
| Q-36 | Encryption at rest and key management (SEC-07) | storage-level encryption with KMS-managed keys; no column encryption of holder data | Decided — [ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md) · pending sign-off (security, platform) | before production |

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

**Decision.** Confirmed as proposed.

### Q-06 Incoming settlement-overdue timer

**Context.** After we send `ACCP`, the CSM settles and sends `ACSC`. UC-06 8b needs a timer for the case where the
confirmation never comes, but gives no value.

**Reasoning.** The Originator PSP's time-out deadline is 20 s after its own acceptance, and the CSM rejects late
transactions after that point. By the time we answer, a few seconds of that budget are already used. A confirmation
that has not arrived 30 s after our answer is therefore overdue in every normal case, and investigating earlier
would mostly create `pacs.028` traffic for confirmations that are merely slow.

**Proposal.** 30 s (`tpe.incoming.settlement-overdue`), retuned in M5 with measured simulator and Kafka latency.

**Decision.** 30 s, retuned once in M5 from the measured settlement latency of the performance runs.

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

**Decision.** Confirmed: only `202` is stored.

### Q-12 Interbank settlement date

**Context.** `pacs.008` needs `IntrBkSttlmDt`. SCT Inst runs 24/7, but the CSM still has a business day: for TIPS it
follows the TARGET business day, which changes in the evening, not at midnight.

**Proposal.** In the MVP the simulator accepts the UTC calendar date. When reconciliation (UC-12) is built, the
gateway takes the current business date from the CSM (or a configured calendar), because `camt.053` statements are
per business day.

**Decision.** Confirmed for the MVP; the CSM business date comes with UC-12.

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

**Decision.** `com.tpe` is kept.

### Q-19 Notifications for rejected incoming payments

A payee is told about an incoming payment only when it is credited. `PaymentRejected` with `direction = INCOMING` is
still emitted (so `payments.events` is complete for every consumer), but notification-service only records it and
does not deliver it. A payee who was never credited has nothing to act on, and a notification would only cause
support calls.

**Decision.** Confirmed as described.

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

**Decision.**

- **SLOs** of [25 §5](25-service-levels.md#5-component-slos) are adopted as the engineering targets. They are
  recalibrated once from the M5 performance and chaos runs; any later change goes through
  [25](25-service-levels.md) with its reason.
- **SLAs** of [25 §4](25-service-levels.md#4-slas) are adopted as the engine's offer. Each party signs its row before
  production (channel teams SLA-01 … -05, payments operations SLA-06 / -07, compliance SLA-08); until then they are
  targets, not commitments.

### Q-22 Funds availability for incoming payments

**Context.** The ledger books an incoming payment when the CSM reports settlement (`CONFIRM_CREDIT` after
`CreditTransferSettled`); until then it is a pending credit and not available to the payee
([ADR-005](../architecture/decisions/ADR-005-ledger-booking-model.md)). SLO-ORC-05 therefore measures funds
availability from the settlement report.

**Decision.** Keep booking on the settlement report. Crediting on our own `ACCP` would make funds available
before the CSM has settled, so the bank would carry the loss whenever settlement fails. Regulation (EU) 2024/886 asks
the payee's PSP to make the amount available within 10 seconds of receiving the payment order. With both TIPS and RT1
the settlement report normally follows our positive answer within a second, so booking on settlement meets that
window. The window is tracked end to end: CUJ-4 in [25 §3](25-service-levels.md#3-critical-user-journeys-and-end-to-end-budgets) gains
"99.9 % of settled incoming payments credited ≤ 10 s after the gateway received the `pacs.008`". When the CSM's report
arrives later than that, the time is attributed to the CSM.

**Sign-off.** Compliance confirms this reading against the current SCT Inst rulebook and the Regulation before M4.
If the rulebook requires availability from our positive confirmation, the booking model
([ADR-005](../architecture/decisions/ADR-005-ledger-booking-model.md)) changes.

### Q-23 Synthetic probes in production

**Context.** At low traffic, burn-rate alerts have too few events, and server-side metrics do not see ingress
failures ([25 §9.3](25-service-levels.md#93-low-traffic-and-synthetic-probes)).

**Proposal.** Read-only probes (own-bank VoP verification, status of a fixed payment) every 30 s from outside the
cluster. An on-us payment of 0.01 EUR every minute between two probe customer accounts would test the whole outgoing
path, but it creates real postings and needs probe customers with a `customerId`.

**Decision.** Production runs the read-only probes only. The 0.01 EUR on-us probe runs in `perf` and staging, where
postings carry no accounting or AML weight. Production relies on the read-only probes plus the low-traffic rules of
[25 §9.3](25-service-levels.md#93-low-traffic-and-synthetic-probes) for the payment path. This needs no approval from
compliance or accounting; asking again later for a production payment probe stays possible.

### Q-24 Service token lifetime and IdP availability

**Context.** [ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md) gives service tokens a
5 min lifetime. Once internal authentication is mandatory, an IdP outage longer than the time between refresh and
expiry stops every internal call, so the IdP becomes a tier-0 dependency ([25 §13](25-service-levels.md#13-risks-and-what-to-revisit) R-3).

**Options.** (a) Keep 5 min, refresh at 50 %, IdP SLO 99.99 %. (b) 30 min lifetime, refresh at 50 % — rides through a
15 min IdP outage at the cost of a longer window for a leaked token. (c) mTLS for service identity, tokens only for
staff.

**Decision.** Option (b): service tokens live **30 min** and are refreshed at 50 %, so the engine rides through an IdP
outage of 15 min (CH-16 pass criterion). The IdP stays at SLO-PLT-03 99.9 % and does not become tier 0. Refresh
failures alert after 5 min. A leaked token is usable for at most 30 min, only for its `aud` and scopes, and only over
TLS ([ADR-016](../architecture/decisions/ADR-016-tls-inside-the-cluster.md)); tokens are never logged. Option (c) is
rejected because ADR-016 issues no client certificates and ADR-012 keeps one identity mechanism.
[ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md) is amended.

**Sign-off.** Security confirms before M5.

### Q-25 Back-office and compliance staffing

**Context.** Exception cases (orchestrator) hold customer money or contradict the CSM; screening cases keep a
customer provisionally frozen. Their resolution time is a human service level (OLA-ORC-01 in
[25 §5.2](25-service-levels.md#52-payment-orchestrator)).

**Decision.** SCT Inst runs every day, and a false-positive sanctions hit freezes a customer until compliance acts, so
back office and compliance are staffed **every day, 08:00–20:00 CET**. OLA-ORC-01 becomes: acknowledged ≤ 1 h of
opening within staffed hours, resolved ≤ 1 staffed day. The same applies to screening cases. Engineering cases
(`LEDGER_ACTION_STUCK`, `DLT_MESSAGE` on `csm.*`) page on-call 24/7 as before.

**Sign-off.** Payments operations and compliance confirm the staffing before production; if they choose business days
only, OLA-ORC-01 reverts to business days and provisional freezes can last over a weekend.

### Q-26 Telemetry hosting

**Context.** [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md) chooses the Grafana stack.
It can run self-hosted or as Grafana Cloud. Telemetry carries pseudonymous payment identifiers (`paymentId`, `txId`,
`uetr`), so a SaaS backend is an ICT outsourcing arrangement under DORA, with data-residency and exit requirements.

**Proposal.** Self-hosted (Mimir, Loki, Tempo, Grafana on in-house object storage, [26 §7](26-observability.md#7-backends)).
The Alloy configuration differs from Grafana Cloud only in endpoints and credentials, so the choice can be revisited.

**Decision.** Self-hosted, as in [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md) option C.
It avoids a DORA ICT outsourcing arrangement and keeps payment identifiers in-house. Grafana Cloud stays a
configuration change if the question is reopened.

### Q-27 Telemetry retention and personal data

**Context.** Names, IBANs and remittance text never reach telemetry ([00 §11](00-engineering-baseline.md#11-logging-tracing-metrics),
[26 §4.4, §6.2](26-observability.md)). Logs and traces do carry `paymentId`, `txId` and `uetr`, which the payments
database links to a person.

**Proposal.** Metrics 13 months (monthly SLO reports, year-on-year), logs 30 d, traces 14 d; Loki access restricted to
on-call ([26 §7](26-observability.md#7-backends)). Telemetry is never the record used for audits or disputes.

**Decision.** The proposal is adopted: metrics 13 months, logs 30 d, traces 14 d; Loki Explore restricted to
`TPE_ONCALL`, dashboards, metrics and traces to `TPE_OBSERVER`. Grafana's audit log records who queried logs. Traces
carry the same identifiers as logs but no free text, which is why they stay readable to all engineers.

**Sign-off.** The data-protection officer confirms the periods and the access groups before M5.

### Q-28 Paging and ticketing tools

**Context.** Alertmanager needs receivers for pages (24/7, tier 0 and 1), tickets, chat and the Watchdog heartbeat
([26 §8.4](26-observability.md#84-routing)).

**Decision.** The engine uses the bank's existing paging and ticketing tools; it does not introduce its own.
Alertmanager reaches them through its native receivers (for example PagerDuty, Opsgenie or a webhook into the issue
tracker), configured per environment in `deploy/observability/alertmanager/`. Whatever tool is named must support:
escalation policies per team, acknowledgement that silences repeats, and an external heartbeat for the Watchdog alert
([26 §8.4](26-observability.md#84-routing)).

**Needs input.** Payments operations names the tools and escalation policies before M5.

### Q-29 Telemetry stack and payment trace model

**Context.** The architecture README listed the production telemetry stack and per-payment tracing as open.

**Proposal.** [ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md) (Micrometer + OTel, Alloy,
Mimir / Loki / Tempo, rules in the Mimir ruler) and [ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md)
(one trace per payment lifecycle, parent / child across `csm.*`, 100 % sampling). The specs follow both.

**Decision.** Both ADRs are accepted (2026-10-22).

### Q-30 Kafka authentication and authorisation

See [ADR-015](../architecture/decisions/ADR-015-kafka-authentication-and-acls.md). Summary: brokers accept only
SASL/SCRAM-SHA-512 over TLS, one principal per service (two users each for rotation), deny by default. ACLs are
generated from `deploy/kafka/topics.yml`, which a consistency test keeps equal to the topic table of
[02 §2](02-messaging-contracts.md#2-topics): only payment-orchestrator writes `csm.outbound.commands`, only
csm-gateway writes `csm.inbound.events`, only ledger-service writes `accounts.events`. `systemtest` runs with ACLs on.
OAUTHBEARER was rejected because it makes the IdP a dependency of every Kafka connection.

**Sign-off.** Security and platform before M5.

### Q-31 TLS inside the cluster

See [ADR-016](../architecture/decisions/ADR-016-tls-inside-the-cluster.md). Summary: every service endpoint serves TLS
in `perf`, staging and `prod` with certificates from an internal CA (cert-manager, 30 d, reloaded without restart);
clients verify host names; no client certificates, because the JWT carries identity. PostgreSQL uses
`sslmode=verify-full`, Kafka `SASL_SSL`. A mesh can replace this later without code changes.

**Sign-off.** Security and platform before M5.

### Q-32 Secret store and rotation

See [ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md). Summary: secrets live in Vault
and reach pods through External Secrets as mounted files; environment-variable secrets fail startup in `prod`. Each
secret has an owner, an interval and a no-downtime procedure. The webhook key and the VoP pepper become keyrings;
`responder_audit` gains `pepper_version` ([13 §5](13-vop-service.md#5-data-model)).

**Sign-off.** Security and platform confirm the store before M5; secret owners confirm the intervals.

### Q-33 Rate limits

See [ADR-018](../architecture/decisions/ADR-018-rate-limiting.md). Summary: in-memory token buckets in payment-api
(per `client_id` and per `sub`, checked before the idempotency guard) and in the vop-service responder (per
requesting PSP, taken from the mTLS certificate in `prod`); `429 RATE_LIMITED` with `Retry-After`. Initial figures:
payments 30 / min and VoP 20 / 10 min and 100 / day per customer, responder 50/s per PSP, per channel 2× its share of
the target load. Limits are approximate (per instance) by design; no new store on the payment path.

**Sign-off.** Channel teams confirm the per-client figures, fraud prevention the per-customer ones, before M5.

### Q-34 Customer authentication and the `accounts` claim

**Context.** payment-api trusts any token with `payments:write` and an `accounts` claim. The specs do not say who
authenticates the customer, whether strong customer authentication (PSD2 RTS, including dynamic linking of the amount
and payee to the authentication) happens, where `accounts` comes from, or how fast revoked access takes effect.

**Proposal.** The channel and the IdP perform SCA with dynamic linking before issuing a payment token; the engine does
not re-check it. `accounts` is filled by the IdP from the bank's entitlement data at token issue. Access tokens live
at most 5 min, which bounds revocation latency. Tokens carry at most 100 IBANs; subjects with more need an
entitlement lookup, which is out of scope today.

**Decision.** The proposal is adopted and is written into SEC-05
([00 §12.3](00-engineering-baseline.md#123-production-requirements)): the engine never re-checks SCA, and payment-api
refuses a token without `accounts` (`403`). The mock issuer in `local` and `systemtest` issues `accounts` the same way.

**Sign-off.** Channel teams and security confirm before payment-api goes beyond the mock issuer in M2.

### Q-35 Audit retention and security events

**Context.** `audit_log` has no retention period and nothing stops a service from changing or deleting its rows.
Staff reads of personal data are not audited. [26](26-observability.md) leaves the security event pipeline out of
scope, and nobody owns it.

**Proposal.** Admin-action audit (`audit_log`, operator transitions in `payment_transitions`, case decisions) kept 10
years, in line with commercial record retention; read-access audit 2 years; `responder_audit` stays at 13 months
(Q-09). Append-only grants as in SEC-06. Security events (authentication failures, `403`, scope violations, staff
role use) go as ECS events through the telemetry gateway to the bank's SIEM, owned by security operations.

**Decision.** The proposal is adopted. 10 years is the longest record-retention period a German bank is likely to need
for actions that move money or lift blocks; compliance may shorten it, never the engine. Retention jobs run under a
separate DB role (SEC-06). Security events are ECS log events with `event.category` = `authentication` /
`authorization`, routed by the telemetry gateway to the SIEM and kept there under the SIEM's own retention.

**Sign-off.** Compliance and the data-protection officer confirm the periods; security operations accepts the feed;
both before production.

### Q-36 Encryption at rest and key management

See [ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md). Summary: storage-level
encryption with KMS-managed keys for PostgreSQL volumes, WAL archive and backups, Kafka log directories, object
storage and etcd. No column encryption of holder data: it would break the VoP and sanctions matching queries and
protect only against our own services. Revisit if backups ever leave the bank's infrastructure.

**Sign-off.** Security and platform before production.

