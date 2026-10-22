# 25 Service levels: SLAs, SLOs and error budgets

What the engine promises to the outside (SLAs), what each component aims for inside (SLOs), how both are measured
(SLIs), and what happens when a budget runs out. The numbers come from the scheme timings (A5), the use-case NFRs,
the service specs and the target load of [Q-13](40-open-questions.md#q-13-target-load). Where this spec adds a number
that no other document has, it says so; those numbers are design targets until the M5 performance runs recalibrate
them (ADR-002 / ADR-004 consequence).

Status ([Q-21](40-open-questions.md#q-21-slo-and-sla-figures)): the SLOs are an **Assumption** the specs follow;
the SLAs **need input** from the parties they are made with.

## 1. Terms and rules

| Term | Meaning here |
|---|---|
| **SLI** | A ratio *good events / valid events*, measured from metrics the services emit (§11). Time-slice SLIs (lag, timer punctuality) count *good minutes / all minutes*. |
| **SLO** | Internal target for an SLI over a **30-day rolling window**. Owned by the team of the component. Drives alerting (§9) and the error-budget policy (§10). |
| **SLA** | Commitment to a party outside the engine (channel teams, the scheme, counterparty PSPs, compliance), over a **calendar month**, with an agreed consequence. Between teams of the same bank this is strictly an OLA; the shape is the same. |
| **Error budget** | `1 − SLO` of the valid events in the window. Spending it is allowed; spending it faster than planned is what pages. |
| **Invariant** | A correctness property with **zero budget** (§6). One violation is a SEV-1 incident, never "within budget". |

Rules:

- Every SLA is **looser** than the SLO behind it, so the SLO alerts while the SLA still holds. Every SLO is looser than
  what the component achieves at the target load in the nightly performance run ([20 §6](20-testing-strategy.md#6-performance)).
- SLOs measure what the user of the component experiences, whatever the cause. Bad events caused by an external party
  (CSM, counterparty PSP, webhook receiver) still count against the SLO; they are tagged `cause=EXTERNAL` where the
  metric allows it, and the error-budget policy treats them differently (§10). SLAs exclude them.
- No maintenance window: SCT Inst runs 24/7/365 (architecture README). Deployments, migrations and failovers spend
  budget like any other failure.
- Load above the declared peak (300 payments/s, Q-13) counts against SLOs and is excluded from SLAs.
- Synthetic probe traffic (§9.3) is included in SLIs.
- Business refusals are **good** events: a `4xx` answer or a payment rejected for `AM04`, `AC04`, `FR01` by a rule
  means the component worked. Technical rejections (`MS03`, `AB03` caused by us, `FR01` from a risk timeout) are bad.

Error budget sizes over 30 days, for orientation. 30 days at the target load of 100 payments/s are 259.2 M
payments; real traffic is lower outside peak hours, so the event counts are an upper bound.

| SLO | Budget | Full-outage equivalent | Bad events at 100 /s |
|---|---|---|---|
| 99.99 % | 0.01 % | 4.3 min | 25 920 |
| 99.95 % | 0.05 % | 21.6 min | 129 600 |
| 99.9 % | 0.1 % | 43.2 min | 259 200 |
| 99.5 % | 0.5 % | 3.6 h | — |
| 99 % | 1 % | 7.2 h | — |

A 30 s PostgreSQL failover with every request failing uses about 12 % of a 99.99 % budget. The budget therefore
allows roughly eight such failovers a month per component, which is the operating assumption behind RTO ≤ 30 s in §8.

## 2. Service tiers

| Tier | Components | Why | Paging |
|---|---|---|---|
| **0 — payment path** | payment-api (initiation, status, VoP proxy), payment-orchestrator, ledger-service posting API, risk-service assessment, vop-service (check, verification, responder), csm-gateway, `messaging` relay and consumers, Kafka, PostgreSQL of these services | A failure rejects or delays money movement, or breaks a scheme obligation. vop-service is tier 0 because payment-api needs a valid `vopReference` ([10 §5.3](10-payment-api.md#53-vop-reference-check)): no VoP, no payment. risk-service is tier 0 because the orchestrator fails closed. | 24/7 |
| **1 — compliance** | risk-service sanctions import and screening, ledger-service block / unblock | Regulation (EU) 2024/886 daily screening (UC-09); misses are regulatory breaches, not customer-visible latency. | 24/7 on run failure; tickets otherwise |
| **2 — information and back office** | notification-service, exception-case API, account admin API, screening-case API, delivery admin API | Never on the payment path (UC-08 NFR). | Fast burn of notifications pages; everything else tickets in business hours |
| **Test-only** | csm-simulator | Never in production (00 §1). | none (§5.10) |

## 3. Critical user journeys and end-to-end budgets

| CUJ | Journey | End-to-end SLO | Components |
|---|---|---|---|
| CUJ-1 | Customer verifies a payee (UC-02) | 99.95 % answered without `5xx`; 99.9 % ≤ 5.5 s | payment-api, vop-service, remote responder |
| CUJ-2 | Customer initiates a payment (UC-01) | 99.95 % accepted or refused without `5xx`; 99 % ≤ 300 ms | payment-api, vop-service, payment-orchestrator |
| CUJ-3 | Outgoing payment reaches a final status (UC-04, UC-05) | 99.9 % final ≤ 20 s of acceptance; 99 % ≤ 3 s at simulator beneficiary delay 300 ms ([20 §6](20-testing-strategy.md#6-performance)) | orchestrator, risk, ledger, relay, Kafka, gateway, CSM |
| CUJ-4 | Incoming payment is answered and credited (UC-06) | 99.99 % answered by our verdict before the answer deadline; 99 % answered ≤ 2 s; 99 % credited ≤ 1 s after the settlement report | gateway, relay, Kafka, orchestrator, risk, ledger |
| CUJ-5 | Customer reads a payment status (UC-07) | 99.95 % without `5xx`; 99 % ≤ 200 ms | payment-api, orchestrator |
| CUJ-6 | Customer channel is notified (UC-08) | 99 % first attempt ≤ 5 s after the final state | orchestrator relay, notification-service |
| CUJ-7 | A newly listed person cannot transact (UC-09) | every list version screened ≤ 1 h after it is stored; every calendar day a completed daily run | risk-service, ledger-service |

### 3.1 Availability composition (CUJ-2)

The initiation call is synchronous through three services, each measured including its own database:
payment-api 99.99 % × vop-service check 99.99 % × orchestrator submit 99.99 % ≈ 99.97 %. That leaves 0.02 % for the
ingress and network in front of payment-api against the 99.95 % journey SLO. A fourth synchronous hop at 99.99 %
would leave almost nothing, so new synchronous dependencies on this path need an explicit review against this budget.

The client sees better availability than the request SLI: a `503` carries `Retry-After: 1` and a retry with the same
`Idempotency-Key` is safe (UC-01 5a). The SLI still counts the first `503` as bad.

### 3.2 Outgoing latency budget (scheme target 10 s, `sendBy` 10 s, deadline 20 s)

```
t=0 accepted (orchestrator commit; 10 s scheme clock starts, UC-01 NFR)
│
├─ risk ≤ 50 ms ─ hold ≤ 50 ms ─ SendCreditTransfer in outbox ................ ≤ 1.0 s   SLO-ORC-03
├─ outbox → Kafka ack ......................................................... ≤ 50 ms   SLO-MSG-01
├─ gateway: consumed → pacs.008 accepted by the CSM ........................... ≤ 100 ms  SLO-GW-02
│                                         our share, p99 ≈ 1.2 s
├─ CSM + Beneficiary PSP + pacs.002 back ............................ remaining ≥ 8.8 s of the 10 s target
├─ gateway: pacs.002 → CreditTransferSettled on Kafka ......................... ≤ 100 ms  SLO-GW-08
└─ orchestrator: event consumed → hold settled, PaymentSettled in outbox ...... ≤ 500 ms  SLO-ORC-05

sendBy t=10 s: the 8.8 s between our p99 and sendBy is what absorbs a relay failover (≤ 5 s, SLO-MSG-02),
               a consumer rebalance and a gateway restart without an AB03.
deadline t=20 s: investigation starts (UC-05).
```

### 3.3 Incoming latency budget (answer deadline 5 s, verdict give-up 4 s)

```
t=0 pacs.008 received by the gateway
├─ CreditTransferReceived in outbox ............................................ ≤ 50 ms   SLO-GW-04
├─ outbox → Kafka ack ........................................................... ≤ 50 ms   SLO-MSG-01
├─ orchestrator: consumed → risk ≤ 50 ms → pending credit ≤ 50 ms → verdict ..... ≤ 1.2 s   SLO-ORC-04
├─ outbox → Kafka ack ........................................................... ≤ 50 ms   SLO-MSG-01
└─ gateway: verdict → answer pacs.002 transmitted ............................... ≤ 100 ms
                                          answer sent, p99 ≤ 2 s                            SLO-GW-06
t=4 s verdict give-up margin (orchestrator rejects MS03 itself)
t=5 s answer deadline: gateway answers RJCT AB05 on its own (bad event for SLO-GW-05)
```

## 4. SLAs

All SLAs: calendar month, 24/7, measured from the same SLIs as the SLO named in the last column. Breach consequence
for every SLA: monthly service report to the party; a breach gets a postmortem shared with the party and a remediation
plan within 10 business days. Scheme and regulatory SLAs additionally follow the escalation in the row.

| SLA | Party | Commitment | Excludes | Escalation | Backing SLO |
|---|---|---|---|---|---|
| SLA-01 | Customer channels | Payment initiation and status: ≥ 99.9 % of requests answered without `5xx` | load above 300 /s; requests rejected with `4xx` | — | SLO-API-01, -03 (99.95 %) |
| SLA-02 | Customer channels | ≥ 99 % of initiation requests answered ≤ 500 ms, status requests ≤ 400 ms | as SLA-01 | — | SLO-API-02, -04 (300 / 200 ms) |
| SLA-03 | Customer channels | Payee verification answered (any result, `NOT_POSSIBLE` included) for ≥ 99.9 % of requests, ≤ 6 s | responder PSP slowness (answered as `NOT_POSSIBLE` in time) | — | SLO-API-05, SLO-VOP-02 |
| SLA-04 | Customer channels | ≥ 99.5 % of accepted outgoing payments final ≤ 20 s; every one final or in an exception case ≤ 1 h | time attributable to the CSM or the Beneficiary PSP | — | SLO-ORC-07 (99.9 %) |
| SLA-05 | Customer channels | Webhooks: ≥ 99 % first attempt ≤ 30 s after the final state; at-least-once retries for 24 h | receivers that do not answer `2xx` within 5 s | — | SLO-NTF-01 (99 % ≤ 5 s) |
| SLA-06 | Scheme / CSM | Our endpoints reachable 24/7; ≥ 99.95 % of incoming `pacs.008` answered (`ACCP` or `RJCT`) before the CSM times them out; no `pacs.008` transmitted after its `sendBy` (invariant) | CSM-side outages | report to the CSM / scheme per its participation rules | SLO-GW-01, -05, INV-5, INV-6 |
| SLA-07 | Counterparty PSPs (VoP) | ≥ 99.9 % of VoP requests answered ≤ 5 s | requests from unknown participants (`401`) | per VoP scheme rules | SLO-VOP-04 (99 % ≤ 1 s) |
| SLA-08 | Compliance (regulatory) | A completed customer screening run every calendar day; every new list version screened ≤ 2 h after the provider publishes it | provider outage (escalated separately when the list is > 24 h stale) | compliance officer same day | SLO-RSK-04, -05, -06 |

## 5. Component SLOs

Columns: **SLI** good / valid; **Source** metric (§11 lists the ones to add); **SLO** over 30 days rolling.
`http.server.requests` is the Spring Boot timer every service already emits; "available" means outcome not
`SERVER_ERROR` and duration below the caller's timeout.

### 5.1 payment-api

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-API-01 | `POST /v1/payments` available | `http.server.requests`; ingress in prod | 99.95 % |
| SLO-API-02 | `POST /v1/payments` ≤ 300 ms / ≤ 1 s | same, SLO buckets | 99 % / 99.9 % |
| SLO-API-03 | `GET /v1/payments/{id}` and `/pain.002` available | same | 99.95 % |
| SLO-API-04 | status requests ≤ 200 ms | same | 99 % |
| SLO-API-05 | `POST /v1/payee-verifications` available; ≤ 5.5 s | same | 99.95 % / 99.9 % |

`503 ORCHESTRATOR_UNAVAILABLE` and `503 VOP_UNAVAILABLE` are bad events. `409 IDEMPOTENCY_KEY_IN_PROGRESS` is good.

### 5.2 payment-orchestrator

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-ORC-01 | `POST /v1/payments` (submit) available | `http.server.requests` | 99.99 % |
| SLO-ORC-02 | submit ≤ 50 ms; `GET /v1/payments/{id}` ≤ 50 ms | same | 99 % |
| SLO-ORC-03 | outgoing via CSM: `accepted_at` → `SendCreditTransfer` in outbox ≤ 1 s / ≤ 5 s. On-us: `accepted_at` → `SETTLED` ≤ 1 s / ≤ 5 s | `tpe.payment.stage.duration{stage=ACCEPTED_TO_SEND \| ACCEPTED_TO_SETTLED_ON_US}` | 99 % / 99.99 % |
| SLO-ORC-04 | incoming: `CreditTransferReceived` consumed → verdict in outbox ≤ 1.2 s / ≤ 3 s | `tpe.payment.stage.duration{stage=RECEIVED_TO_VERDICT}` | 99 % / 99.99 % |
| SLO-ORC-05 | CSM outcome consumed → ledger effect done and final event in outbox ≤ 500 ms / ≤ 5 s (incoming: this is when the payee's funds are booked) | `tpe.payment.stage.duration{stage=OUTCOME_TO_FINAL}` | 99 % / 99.99 % |
| SLO-ORC-06 | accepted payments **not** rejected for a technical cause of ours (`MS03`, `AB03` with `NOT_SENT`, `FR01` from risk timeout / unavailability), per direction | `tpe.payment.rejected{direction,reason,cause}` vs `tpe.payment.duration` count | 99.95 % |
| SLO-ORC-07 | outgoing CSM payments final ≤ 20 s of acceptance | `tpe.payment.duration{direction=OUTGOING}` | 99.9 % |
| SLO-ORC-08 | timer punctuality: minutes with `tpe.worker.lag{worker=payment-timers \| saga-sweeper}` ≤ 500 ms | `tpe.worker.lag` | 99.9 % of minutes |
| SLO-ORC-09 | `csm.inbound.events` freshness: minutes with consumer lag ≤ 1 s | `tpe.consumer.lag.seconds` | 99.9 % of minutes |
| SLO-ORC-10 | exception-case API available; ≤ 1 s | `http.server.requests` | 99.5 % / 99 % |

OLA-ORC-01 (back office, [Q-25](40-open-questions.md#q-25-back-office-and-compliance-staffing)): open exception
cases acknowledged ≤ 1 h of opening in staffed hours, resolved ≤ 1 business day. `LEDGER_ACTION_STUCK` and
`DLT_MESSAGE` on `csm.*` page engineering on-call 24/7: both mean customer money is held or a fact was not applied.

### 5.3 ledger-service

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-LED-01 | posting API (holds, pending credits) available; `409 CONCURRENT_MODIFICATION` is bad | `http.server.requests{uri=/v1/holds/**,/v1/pending-credits/**}` | 99.99 % |
| SLO-LED-02 | posting operation ≤ 50 ms / ≤ 200 ms service-side at ≤ 300 ops/s ([12 §8](12-ledger-service.md#8-non-functional-requirements)) | same | 99 % / 99.99 % |
| SLO-LED-03 | account API (back office and risk-service block / unblock) available; ≤ 300 ms (entries page ≤ 500 ms) | same | 99.9 % / 99 % |
| SLO-LED-04 | account lifecycle change → event acknowledged on `accounts.events` ≤ 1 s | `tpe.outbox.publish.latency` | 99 % |

Correctness: INV-1, INV-2, INV-12 (§6). Durability: RPO 0 (§8).

### 5.4 vop-service

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-VOP-01 | reference check available; ≤ 50 ms (it sits inside the 300 ms initiation budget) | `http.server.requests` | 99.99 % / 99 % |
| SLO-VOP-02 | verification (requesting) answered `201` (`NOT_POSSIBLE` included); own-bank payee ≤ 200 ms; other bank ≤ 5.2 s | `tpe.vop.requests{role=REQUESTER,route,result,cause}` | 99.95 % / 99 % / 99.9 % |
| SLO-VOP-03 | verifications **not** `NOT_POSSIBLE` for a cause of ours (`cause=LOCAL`; responder timeout / error is `EXTERNAL`) | same | 99.9 % |
| SLO-VOP-04 | responder (scheme endpoint) available for known participants; ≤ 1 s / ≤ 2 s at 200 req/s (UC-03, VOP-AC-09) | `tpe.vop.requests{role=RESPONDER}` | 99.95 % / 99 % / 99.9 % |
| SLO-VOP-05 | holder projection freshness: minutes with `accounts.events` consumer lag ≤ 5 s (a new account answers `NOT_POSSIBLE` until it arrives) | `tpe.consumer.lag.seconds` | 99.9 % of minutes |
| SLO-VOP-06 | payee names cleared ≤ 2 h after the retention passes ([13 §6.3](13-vop-service.md#63-retention), Q-09) | retention job metric `tpe.vop.retention.overdue` (rows past retention not cleared) = 0 | 100 % (miss = incident) |

### 5.5 risk-service

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-RSK-01 | assessment available. Every bad event is a rejected payment (fail closed, UC-04 2c) | `http.server.requests{uri=/v1/risk/assessments}` | 99.99 % |
| SLO-RSK-02 | assessment ≤ 50 ms / ≤ 250 ms service-side (the incoming client timeout is 300 ms) | `tpe.risk.assessment.duration{direction,decision}` | 99 % / 99.99 % |
| SLO-RSK-03 | rule parameter change effective ≤ 60 s | refresh age gauge `tpe.risk.rules.age` | 99.9 % of minutes |
| SLO-RSK-04 | sanctions list freshness: a provider version is stored as current ≤ 30 min after publication (poll 15 min) | `tpe.sanctions.list.age` | every version |
| SLO-RSK-05 | a `LIST_UPDATE` run completes ≤ 1 h after the version is stored; a `DAILY` run completes every calendar day (by 04:00 UTC for the 03:00 start) | `tpe.sanctions.run.duration{type}`, `tpe.sanctions.run.last_success{type}` | every run |
| SLO-RSK-06 | provisional freeze: hit found → `SANCTIONS_PROVISIONAL` block committed in the ledger ≤ 1 min | `tpe.sanctions.freeze.latency` | 99.9 %; none later than 15 min |
| SLO-RSK-07 | holder projection freshness as SLO-VOP-05 | `tpe.consumer.lag.seconds` | 99.9 % of minutes |
| SLO-RSK-08 | screening-case API available; ≤ 1 s | `http.server.requests` | 99.5 % / 99 % |

SLO-RSK-04 … -06 count a handful of events per month, so a percentage has no meaning: each miss is an incident and
a postmortem (§10), and the "budget" is zero. The only percentage is SLO-RSK-06, which counts accounts, not runs.

### 5.6 csm-gateway

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-GW-01 | inbound endpoint (CSM → us: `pacs.008`, `pacs.002`) available; an XSD-invalid message answered `FF01` is good | `http.server.requests{uri=/csm/v1/inbound}`; ingress in prod | 99.99 % |
| SLO-GW-02 | `SendCreditTransfer` consumed → `pacs.008` accepted by the CSM (`2xx`) ≤ 100 ms / ≤ 2 s | `tpe.csm.stage.duration{stage=COMMAND_TO_TRANSMITTED}` | 99 % / 99.99 % |
| SLO-GW-03 | `SendCreditTransfer` **not** ending `NOT_SENT AB03` | `tpe.csm.notsent{reason}` vs commands | 99.99 % |
| SLO-GW-04 | inbound `pacs.008` → `CreditTransferReceived` in outbox ≤ 50 ms | `tpe.csm.stage.duration{stage=PACS008_TO_EVENT}` | 99 % |
| SLO-GW-05 | answers decided by `VERDICT`, not by `DEADLINE` (validation rejects excluded) | `tpe.csm.answer{decidedBy}` | 99.99 % |
| SLO-GW-06 | `pacs.008` received → answer `pacs.002` transmitted ≤ 2 s; before the answer deadline | `tpe.csm.answer.margin` (deadline − transmitted) ≥ 3 s / ≥ 0 | 99 % / 99.99 % |
| SLO-GW-07 | answer deadline worker fires ≤ 100 ms after `answer_deadline` ([15 §8](15-csm-gateway.md#8-non-functional-requirements)) | `tpe.worker.lag{worker=answer-deadline}` | 99 % of minutes |
| SLO-GW-08 | inbound `pacs.002` → `CreditTransferSettled` / `Rejected` in outbox ≤ 100 ms | `tpe.csm.stage.duration{stage=PACS002_TO_EVENT}` | 99 % |
| SLO-GW-09 | `csm.outbound.commands` freshness: minutes with consumer lag ≤ 1 s (lag eats the `sendBy` margin directly) | `tpe.consumer.lag.seconds` | 99.9 % of minutes |

Not budgeted, alert on any: `tpe.csm.uncorrelated`, `CreditTransferNotSent(FF01)` (a builder bug), INV-5, INV-6.

### 5.7 notification-service

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-NTF-01 | final event written (`tpe-occurred-at`) → first delivery attempt started ≤ 5 s / ≤ 30 s | `tpe.notification.dispatch.delay` | 99 % / 99.9 % |
| SLO-NTF-02 | retry attempts start ≤ 5 s after their scheduled time | `tpe.notification.attempt.lateness` | 99.9 % |
| SLO-NTF-03 | subscription API available; ≤ 300 ms | `http.server.requests` | 99.9 % / 99 % |
| SLO-NTF-04 | delivery admin API available | same | 99.5 % |

Receiver success rate (`tpe.notification.deliveries{outcome}`) is an indicator, not an SLO: the receiver owns it.
No-loss is INV-13. Isolation: a notification-service outage MUST NOT move any tier-0 SLI (UC-08 NFR); see §12.

### 5.8 `messaging` (outbox relay, consumers) — per service

| Id | SLI | Source | SLO |
|---|---|---|---|
| SLO-MSG-01 | outbox insert → Kafka ack ≤ 50 ms / ≤ 1 s ([01 §3.3](01-shared-libraries.md#33-outbox-relay)) | `tpe.outbox.publish.latency` | 99 % / 99.99 % |
| SLO-MSG-02 | relay failover: minutes in which exactly one relay holds the lock, or the gap is ≤ 5 s | `tpe.relay.active` (1 on the holder) summed per service | 99.99 % of minutes |
| SLO-MSG-03 | `payments.events` freshness: minutes with consumer lag ≤ 10 s | `tpe.consumer.lag.seconds` | 99.9 % of minutes |

`csm.*` lag is owned by the consuming service (SLO-ORC-09, SLO-GW-09), `accounts.events` by vop / risk.
DLT publishes are not budgeted: any `tpe.dlt.messages` increase alerts.

### 5.9 Platform

| Id | Component | SLO | Notes |
|---|---|---|---|
| SLO-PLT-01 | PostgreSQL, tier-0 databases (`payments`, `ledger`, `csm`, `risk`, `api`, `vop`) | 99.99 % available; failover RTO ≤ 30 s; RPO per §8 | the service SLIs include their DB; this SLO is for the platform team |
| SLO-PLT-02 | Kafka | 99.99 % produce available with `acks=all`; produce p99 ≤ 20 ms; one broker lost → no unavailability | RF 3, `min.insync.replicas=2` ([02 §2](02-messaging-contracts.md#2-topics)) |
| SLO-PLT-03 | Identity provider (JWKS, from M5 also service tokens) | 99.99 % while service tokens are mandatory, else 99.9 % | see §13 R-3 and [Q-24](40-open-questions.md#q-24-service-token-lifetime-and-idp-availability) |
| SLO-PLT-04 | Time sync | 100 % of minutes with host clock offset < 100 ms; alert at 50 ms | [00 §6](00-engineering-baseline.md#6-time) |

### 5.10 csm-simulator

No SLO: test-only, never deployed to production. In performance runs it stands in for the CSM, so it MUST NOT
distort our numbers: its own processing overhead beyond the configured scenario delays is reported with every
Gatling run and MUST stay ≤ 10 ms p99, otherwise the run is invalid.

### 5.11 External dependencies (assumed, not owned)

| Dependency | What our budgets assume | What we do when it is worse |
|---|---|---|
| CSM (TIPS / RT1) | reachable 24/7; CSM + Beneficiary PSP ≤ 8.8 s of the 10 s target | `sendBy` guard, investigation (UC-05); bad events tagged `EXTERNAL` |
| Counterparty VoP responders | answer ≤ 5 s | `NOT_POSSIBLE` at 5 s (UC-02 3a) |
| Sanctions list provider | new version within 15 min polling | keep current version, alert; list > 24 h stale → compliance escalation (SLA-08) |
| Webhook receivers | `2xx` ≤ 5 s | 24 h retry schedule (UC-08) |

## 6. Correctness invariants (zero budget)

The invariants of [20 §3](20-testing-strategy.md#3-global-invariants) are tested in every system test. In production
the ones that money depends on also run continuously, as reconciliation checks:

| Check | Invariants | Frequency | On violation |
|---|---|---|---|
| Journal balance and non-negative balances | INV-1, INV-2, INV-12 | every 5 min | SEV-1, freeze payment-path deploys |
| Holds / pending credits match payment outcomes; no `ACTIVE` memo older than 1 h for a final payment | INV-3, INV-4 | every 5 min | SEV-1 |
| Nothing stuck: no overdue `pending_action`, no `PENDING` gateway row past its give-up window | INV-11 | every minute | SEV-2 (SEV-1 if growing) |
| Exactly one final event per final payment | INV-7 | hourly | SEV-1 |
| No `pacs.008` after `sendBy`; exactly one answer per incoming transaction | INV-5, INV-6 | hourly, from the gateway journal | SEV-1 + scheme report |
| Ledger agrees with the CSM | INV-10 | with UC-12 reconciliation (later) | SEV-1 |
| No personal data in logs | INV-14 | daily log scan | SEV-2, data-protection officer informed |

## 7. Dependency check: timeouts against SLOs

A caller's timeout must sit well above the callee's high-percentile SLO, or the callee's slow tail turns into the
caller's errors.

| Caller → callee | Caller timeout | Callee SLO | Headroom | Verdict |
|---|---|---|---|---|
| payment-api → orchestrator submit | 1 s | 99 % ≤ 50 ms (SLO-ORC-02) | 20× | ok |
| payment-api → orchestrator status | 300 ms | 99 % ≤ 50 ms | 6× | ok |
| payment-api → vop check | 300 ms | 99 % ≤ 50 ms (SLO-VOP-01) | 6× | ok |
| payment-api → vop verify | 5.5 s | 99.9 % ≤ 5.2 s (SLO-VOP-02) | 300 ms | tight by design: vop-service answers `NOT_POSSIBLE` at 5 s |
| orchestrator → risk (out / in) | 500 / 300 ms | 99.99 % ≤ 250 ms (SLO-RSK-02) | 2× / 1.2× | ok; incoming is the tightest hop of the system |
| orchestrator → ledger | `min(1 s, remaining − margin)` | 99.99 % ≤ 200 ms (SLO-LED-02) | 5× | ok |
| gateway → CSM | 1 s read | external | — | transmit retries until `sendBy` / transmit window |
| vop → remote responder | 5 s | external | — | `NOT_POSSIBLE` |
| notification → receiver | 5 s read | external | — | retry schedule |

## 8. Durability and recovery

Scope: loss of one instance, one database node or one broker within a site. Loss of a whole site or region is not
designed yet (§13).

| Store | RPO | RTO | Why this RPO | How |
|---|---|---|---|---|
| `payments` (orchestrator) | 0 | ≤ 30 s | a lost commit loses a payment, its outbox command or a timer | synchronous standby (`synchronous_commit = on`), automatic failover |
| `ledger` | 0 | ≤ 30 s | a lost posting is lost money | same; PITR backups 30 d |
| `csm` (gateway journal) | 0 | ≤ 30 s | the journal decides "sent / not sent" (ADR-004) | same |
| `risk` | 0 | ≤ 30 s | screening cases and freeze decisions are compliance records | same |
| `api` (idempotency) | ≤ 1 min | ≤ 30 s | a lost key is safe: the orchestrator deduplicates by `commandId` | async replica allowed |
| `vop` | ≤ 1 min | ≤ 30 s | lost references are re-verified; the projection rebuilds from the compacted topic. RTO stays tier 0: no VoP, no payment | async replica + runbook: after a failover with loss, reset the service's consumer groups to failover time − 5 min (inbox deduplicates, CH-09) |
| `notification` | ≤ 1 min | ≤ 5 min | lost deliveries are recovered by re-consuming | async replica + the same consumer-group reset runbook |
| Kafka | 0 | broker loss: none | — | `acks=all`, RF 3, `min.insync.replicas=2` |

Synchronous replication adds a commit round trip (low single-digit milliseconds in one site); the 50 ms posting
SLO already includes it. Restore drill from backups quarterly; the drill's measured restore time is published.

## 9. Alerting

### 9.1 Burn-rate alerts (every SLO with a percentage)

Multi-window, multi-burn-rate on the 30-day budget. An alert fires only when both windows exceed the rate, so it
resets quickly after recovery.

| Severity | Burn rate | Long window | Short window | Budget used when it fires | Tier 0 | Tier 1 | Tier 2 |
|---|---|---|---|---|---|---|---|
| Page | 14.4× | 1 h | 5 min | 2 % | page | page | page (SLO-NTF-01 only) |
| Page | 6× | 6 h | 30 min | 5 % | page | ticket | ticket |
| Ticket | 1× | 3 d | 6 h | 10 % | ticket | ticket | ticket |

Example, SLO-ORC-01 fast burn (`application` is a common tag from the shared starter):

```promql
(
  sum(rate(http_server_requests_seconds_count{application="payment-orchestrator",method="POST",uri="/v1/payments",outcome="SERVER_ERROR"}[1h]))
  / sum(rate(http_server_requests_seconds_count{application="payment-orchestrator",method="POST",uri="/v1/payments"}[1h]))
) > (14.4 * 0.0001)
and
(
  sum(rate(http_server_requests_seconds_count{application="payment-orchestrator",method="POST",uri="/v1/payments",outcome="SERVER_ERROR"}[5m]))
  / sum(rate(http_server_requests_seconds_count{application="payment-orchestrator",method="POST",uri="/v1/payments"}[5m]))
) > (14.4 * 0.0001)
```

Latency SLIs use the histogram bucket at the SLO threshold (`le="0.3"` for 300 ms), which exists only if the
boundary is configured (§11). Burn-rate rules are generated from one SLO definition file per service
(`deploy/slo/<service>.yml`: id, SLI query, objective), not written by hand.

### 9.2 Cause alerts (page regardless of budget)

Symptoms page through §9.1; these causes page because they precede an invariant break or remove a safety margin:

| Alert | Condition | Severity |
|---|---|---|
| Invariant check failed | any §6 check | SEV-1 |
| DLT message | `tpe.dlt.messages` increases on `csm.*`; other topics → ticket | SEV-2 |
| No active relay | `sum(tpe.relay.active{service})` = 0 for > 5 s | SEV-1 for orchestrator / gateway |
| Two active relays | sum > 1 for longer than 5 s (an old leader that has not noticed it lost the lock; a shorter overlap is expected during failover and safe, ADR-006 amendment) | SEV-2 |
| Synchronous standby missing | tier-0 DB has no sync standby for > 1 min (RPO 0 no longer holds) | SEV-2 |
| Clock offset | > 50 ms on any host | SEV-2; > 100 ms SEV-1 |
| Uncorrelated `pacs.002` | `tpe.csm.uncorrelated` increases | SEV-2 |
| Sanctions run | a run failed, or no `DAILY` success by 04:00 UTC | SEV-2, compliance informed |
| Sanctions list stale | `tpe.sanctions.list.age` > 24 h | SEV-2, compliance escalation |
| Capacity | any tier-0 service above 50 % of its measured saturation throughput, or relay throughput above 50 % of its measured maximum, for 15 min | ticket |

### 9.3 Low traffic and synthetic probes

At night the request rate falls and a handful of errors can look like a fast burn. Every burn-rate rule requires a
minimum of 100 valid events in the short window; below that, alerting relies on synthetic probes:

- VoP verification against an own-bank probe account every 30 s (no money moves).
- `GET /v1/payments/{id}` of a fixed probe payment every 30 s.
- An on-us payment of 0.01 EUR between two probe customer accounts every minute — only with compliance and accounting
  approval ([Q-23](40-open-questions.md#q-23-synthetic-probes-in-production)).
- Probes run outside the cluster, so they also see ingress, DNS and TLS failures that server-side SLIs miss.

## 10. Error-budget policy

| State of the component's budget (30 d) | Policy |
|---|---|
| > 50 % left | normal delivery |
| 25–50 % left | changes to the component ship behind a canary with an explicit rollback trigger; reliability items go to the top of the next iteration |
| < 25 % left | only low-risk changes; on-call reviews each change before deploy |
| exhausted | feature freeze for the component until the rolling budget is positive again; only fixes and reliability work ship; postmortem required |
| invariant violated (§6) | SEV-1; all payment-path deployments frozen until the root cause is known; postmortem |

- Budget spent on `cause=EXTERNAL` events does not trigger the freeze, but it triggers a review of whether our
  timeouts, retries and margins absorbed the external failure as designed.
- The freeze decision is the component owner's; the product owner can override it in writing.
- Postmortems are blameless and list the budget consumed per SLO.
- Incident severity: SEV-1 = tier-0 fast burn, any invariant, or an SLA about to be breached; SEV-2 = tier-0 slow
  burn, tier-1 fast burn, §9.2 SEV-2 alerts; SEV-3 = tickets.
- Review: SLO report per component monthly; SLAs reviewed quarterly with their parties; every SLO recalibrated once
  after the M5 performance runs, and whenever the nightly run shows a component achieving far better or worse than
  its SLO.

## 11. Metrics to add

Existing metrics are listed in the service specs. The SLIs above need these as well (owner in brackets). All
durations are Micrometer timers with SLO boundaries configured via `management.metrics.distribution.slo.<meter>`,
so every threshold in §5 is an exact histogram bucket:

| Metric | Tags | Owner |
|---|---|---|
| `http.server.requests` (Spring Boot) — boundaries `50ms,100ms,200ms,300ms,500ms,1s,2s,5s,5.5s` | built-in | every service |
| `tpe.payment.stage.duration` | `direction`, `stage` = `ACCEPTED_TO_SEND`, `ACCEPTED_TO_SETTLED_ON_US`, `RECEIVED_TO_VERDICT`, `OUTCOME_TO_FINAL` | orchestrator |
| `tpe.payment.rejected` (counter) | `direction`, `reason`, `cause` = `BUSINESS` \| `TECHNICAL` \| `EXTERNAL` (derived from the transition `cause`) | orchestrator |
| `tpe.payment.duration` — boundaries `1s,3s,10s,20s` | existing | orchestrator |
| `tpe.csm.stage.duration` | `stage` = `COMMAND_TO_TRANSMITTED`, `PACS008_TO_EVENT`, `PACS002_TO_EVENT` | csm-gateway |
| `tpe.csm.answer.margin` — boundaries `0s,1s,3s` | existing | csm-gateway |
| `tpe.vop.requests` | `role` = `REQUESTER` \| `RESPONDER`, `route` = `OWN` \| `REMOTE`, `result`, `cause` = `LOCAL` \| `EXTERNAL` \| `NONE` | vop-service |
| `tpe.vop.retention.overdue` (gauge) | — | vop-service |
| `tpe.risk.assessment.duration` | `direction`, `decision` | risk-service |
| `tpe.risk.rules.age` (gauge) | — | risk-service |
| `tpe.sanctions.list.age` (gauge: now − publication of the current version) | — | risk-service |
| `tpe.sanctions.run.duration`, `tpe.sanctions.run.last_success` (gauge, epoch s) | `type` = `DAILY` \| `LIST_UPDATE` \| `MANUAL` | risk-service |
| `tpe.sanctions.freeze.latency` | — | risk-service |
| `tpe.notification.dispatch.delay`, `tpe.notification.attempt.lateness` | `format` | notification-service |
| `tpe.notification.deliveries` (counter) | `outcome` = `DELIVERED` \| `RETRY` \| `FAILED` \| `GONE` | notification-service |
| `tpe.relay.active` (gauge 0 / 1) | — | `messaging` |
| `tpe.consumer.lag.seconds` (gauge per partition: now − timestamp of the oldest unconsumed record; computed by a lag exporter from committed offsets, so a stalled consumer is visible) | `topic`, `group` | platform |

## 12. Verification

- The nightly performance run ([20 §6](20-testing-strategy.md#6-performance)) reports every latency SLO of §5 by id;
  its targets are the same numbers. A run where any SLO is not met fails, as today.
- The failure-injection runs ([20 §5](20-testing-strategy.md#5-failure-injection-scenarios)) record budget consumed
  per SLO, so the cost of a failover or a restart is known before production. Expectations: CH-06 (DB failover)
  errors only within the 30 s RTO; CH-01 / CH-02 / CH-11 no `AB03` (SLO-GW-03) and no `DEADLINE` answers
  (SLO-GW-05); CH-05 and CH-10 technical rejections only during the outage (SLO-ORC-06).
- CH-12 checks the isolation of notification-service (§5.7): no tier-0 SLI moves while it is down. CH-13 checks a
  relay that is lost without closing its socket (frozen or partitioned leader): another relay publishes within 5 s
  (SLO-MSG-02, §13 R-1).
- Burn-rate rules are unit-tested with Prometheus rule tests (`promtool test rules`) against recorded series of a
  CH run.

## 13. Risks and what to revisit

| # | Risk | Effect on SLOs | Proposal |
|---|---|---|---|
| R-1 | The relay's advisory lock is held by a session; if the holder's **node** disappears, PostgreSQL keeps the session until TCP keepalive gives up (default: hours). A process kill closes the socket and is fine. | No relay → every payment accepted meanwhile ends `AB03`; incoming verdicts miss the deadline (SLO-GW-03, -05) | Decided in the [ADR-006 amendment](../architecture/decisions/ADR-006-ordered-outbox-relay.md#amendment-2026-10-22-bounded-failover): heartbeat plus `idle_session_timeout = 3s` on the lock session, progress watchdog, no-skipping rule; failover ≤ 5 s; a resumed old leader causes duplicates only. Verified by CH-13 and MSG-AC-08. |
| R-2 | Consumer-group rebalances during rolling deploys stop `csm.*` consumption for seconds | lag spikes against `sendBy` and the answer deadline (SLO-ORC-09, SLO-GW-09) | cooperative-sticky assignor and static membership (`group.instance.id`, session timeout above restart time); deploy one instance at a time |
| R-3 | Service tokens live 5 min ([ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md)); an IdP outage longer than the refresh lead stops every internal call from M5 on | the IdP becomes an undeclared tier-0 dependency | refresh at 50 % of lifetime and alert on refresh failures; longer lifetime or IdP SLO 99.99 % — [Q-24](40-open-questions.md#q-24-service-token-lifetime-and-idp-availability) |
| R-4 | Server-side SLIs do not see failures in front of the service | edge SLOs look better than reality | ingress metrics in production and external probes (§9.3) |
| R-5 | One relay per service is a throughput ceiling (ADR-006: 4–8× peak) | latency SLOs degrade before errors appear | capacity alert at 50 % (§9.2); sharding by key is the upgrade path |
| R-6 | Funds for an incoming payment are booked on `CreditTransferSettled`, not when we send `ACCP` | SLO-ORC-05 measures from settlement; if the scheme requires availability from the positive confirmation, the journey is defined wrongly | [Q-22](40-open-questions.md#q-22-funds-availability-for-incoming-payments) |
| R-7 | Site or region loss | RPO / RTO of §8 do not apply | needs its own ADR together with the "24/7 operation" gap of the architecture README |

Revisit as the system grows: per-scheme SLOs when RT1 and TIPS are both connected; SLOs for recall (UC-11) and
reconciliation (UC-12); CSM liquidity rejections as a separate `EXTERNAL` cause; per-channel SLAs once channels
differ in volume.
