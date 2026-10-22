# 11 payment-orchestrator

Architecture: [C4 components](../architecture/c4/03-components-payment-orchestrator.puml),
[ADR-003](../architecture/decisions/ADR-003-orchestrated-saga.md), [ADR-004](../architecture/decisions/ADR-004-csm-gateway-owns-scheme-timing.md),
[ADR-008](../architecture/decisions/ADR-008-resumable-saga-steps.md), [ADR-009](../architecture/decisions/ADR-009-ledger-tombstones.md),
[UC-01](../architecture/use-cases/UC-01-initiate-instant-credit-transfer.md) 6–7,
[UC-04](../architecture/use-cases/UC-04-execute-outgoing-payment.md), [UC-05](../architecture/use-cases/UC-05-resolve-missing-csm-response.md),
[UC-06](../architecture/use-cases/UC-06-receive-incoming-payment.md).

## 1. Responsibilities

- Own the payment record and its state machine for both directions.
- Run the outgoing saga (risk → hold → send or on-us → settle / release) and the incoming saga (risk → pending
  credit → verdict → follow `AnswerSent` → confirm / cancel).
- Own the 20 s outgoing deadline, the incoming settlement-overdue timer and investigation retries.
- Follow the facts csm-gateway reports; never decide what is sent to the CSM (ADR-004).
- Emit `PaymentSettled` / `PaymentRejected`.
- Exception cases for the back office.

Not responsible for: idempotency of the customer request (payment-api), ISO XML (gateway), balances (ledger).

## 2. Module layout

| Package | C4 component |
|---|---|
| `api.command` | Payment command API |
| `api.ops` | Exception case API |
| `api.csm` | CSM event listener |
| `application.outgoing` | Outgoing payment saga |
| `application.incoming` | Incoming payment saga |
| `application.runner` | Saga runner (§6.3) |
| `application.timers` | Deadline scheduler handlers |
| `application.cases` | Exception case service |
| `domain.payment` | `Payment`, `PaymentState`, `PendingAction`, payment state machine, reason-code mapping |
| `infrastructure.risk` | Risk client |
| `infrastructure.ledger` | Ledger client |
| `infrastructure.persistence` | Payment repository, timers, cases |
| `infrastructure.messaging` | outbox / listener wiring |

## 3. REST API

### 3.1 Payment command API (internal, caller: payment-api)

**`POST /v1/payments`** — `SubmitPayment`

```json
{
  "commandId": "5b0a7c0e-…",
  "clientId": "mobile-app",
  "endToEndId": "INV-2026-0042",
  "debtor":   { "name": "Anna Schmidt", "iban": "DE…" },
  "creditor": { "name": "Max Mustermann", "iban": "DE…", "bic": "PEERDEFFXXX" },
  "amountMinor": 12345,
  "currency": "EUR",
  "remittanceInformation": "Invoice 42",
  "vopReference": "VOP-01JAB…"
}
```

| Result | Response |
|---|---|
| New payment | `201 { "paymentId", "state": "RECEIVED", "txId", "acceptedAt" }` |
| Payment exists for `commandId`, same request hash | `200` with the existing payment's current `state` (UC-01 6a) |
| Payment exists for `commandId`, different hash | `409 COMMAND_CONFLICT` (payment-api bug; alert) |
| Creditor bank not in directory / not reachable | `422 CREDITOR_AGENT_NOT_REACHABLE` (payment-api validates first; defensive) |

Processing (one transaction): insert payment (`direction = OUTGOING`, `state = RECEIVED`, `accepted_at = clock.now()`,
`send_by = accepted_at + send-by-offset`, `deadline_at = accepted_at + deadline-offset`, `on_us` from
`BankDirectory.isOwn(creditor.iban)`, `creditor_agent_bic` from directory if not given, `pending_action =
ASSESS_RISK`), insert `OUTGOING_DEADLINE` timer at `deadline_at`, insert transition record. Unique violation on
`command_id` → re-read and return `200`. After commit the saga runner starts the action (§6.3). p99 ≤ 50 ms.

**`GET /v1/payments/{paymentId}`**

```json
200 {
  "paymentId": "…", "direction": "OUTGOING", "onUs": false, "state": "SENT_TO_CSM", "reasonCode": null,
  "txId": "…", "uetr": "…", "endToEndId": "…", "amountMinor": 12345, "currency": "EUR",
  "debtor": { "name": "…", "iban": "…" }, "creditor": { "name": "…", "iban": "…" },
  "remittanceInformation": "…", "acceptedAt": "…", "finalisedAt": null
}
```

`404 PAYMENT_NOT_FOUND`. payment-api performs the ownership check and the status mapping (UC-07).

### 3.2 Exception case API (role `BACKOFFICE_OPERATOR`)

| Method & path | Purpose |
|---|---|
| `GET /v1/exception-cases?status=OPEN&type=` | List cases (cursor pagination) |
| `GET /v1/exception-cases/{caseId}` | Case with payment, transition history and CSM events |
| `POST /v1/exception-cases/{caseId}/resolve` | Resolve a case |

Resolve body: `{ "resolution": "<value>", "reasonCode": "AB05", "comment": "…" }`

| Case type | Allowed resolutions | Effect |
|---|---|---|
| `UNRESOLVED_INVESTIGATION` | `RETRY_INVESTIGATION` | new `RequestPaymentStatus`, retry schedule restarts |
| | `CONFIRMED_SETTLED` | as if `CreditTransferSettled` arrived (settle hold / confirm credit) |
| | `CONFIRMED_REJECTED` (+ `reasonCode`) | as if `CreditTransferRejected` arrived (release / cancel) |
| `CONTRADICTING_CSM_OUTCOME` | `ACKNOWLEDGED` | none — money correction is a manual ledger process outside the MVP (UC-12) |
| `LEDGER_ACTION_STUCK` | `RETRY_NOW` | resets `next_action_at` |
| `DLT_MESSAGE` | `ACKNOWLEDGED` | none |

Every resolution is written to `exception_cases` and `payment_transitions` with the operator's `sub`.

## 4. Messaging

| Direction | Topic | Messages | Dedup |
|---|---|---|---|
| produce | `csm.outbound.commands` | `SendCreditTransfer`, `RequestPaymentStatus`, `AcceptCreditTransfer`, `RejectCreditTransfer` | — |
| produce | `payments.events` | `PaymentSettled`, `PaymentRejected` | — |
| consume | `csm.inbound.events` | `CreditTransferReceived`, `AnswerSent`, `CreditTransferSettled`, `CreditTransferRejected`, `CreditTransferNotSent` | `<debtorAgentBic>:<txId>:<type>:<outcome>` ([02 §4](02-messaging-contracts.md), ADR-010) |

`DltHook` for `csm.inbound.events.dlt`: open `DLT_MESSAGE` case linked to the payment if the `txId` can be read from
the key.

## 5. Data model

```sql
CREATE TABLE payments (
  payment_id        UUID PRIMARY KEY,
  direction         VARCHAR(8)  NOT NULL CHECK (direction IN ('OUTGOING','INCOMING')),
  state             VARCHAR(20) NOT NULL,
  reason_code       VARCHAR(4),
  on_us             BOOLEAN NOT NULL DEFAULT false,
  command_id        UUID UNIQUE,                 -- outgoing only
  client_id         VARCHAR(100),
  request_hash      CHAR(64),
  tx_id             VARCHAR(35) NOT NULL,
  uetr              UUID NOT NULL,
  end_to_end_id     VARCHAR(35) NOT NULL,
  msg_id            VARCHAR(35),                 -- incoming: pacs.008 MsgId
  amount_minor      BIGINT NOT NULL CHECK (amount_minor > 0),
  currency          CHAR(3) NOT NULL CHECK (currency = 'EUR'),
  debtor_name       VARCHAR(70) NOT NULL,
  debtor_iban       VARCHAR(34) NOT NULL,
  debtor_agent_bic  VARCHAR(11) NOT NULL,
  debtor_account_id UUID,
  creditor_name     VARCHAR(70) NOT NULL,
  creditor_iban     VARCHAR(34) NOT NULL,
  creditor_agent_bic VARCHAR(11) NOT NULL,
  creditor_account_id UUID,
  remittance        VARCHAR(140),
  vop_reference     VARCHAR(40),
  accepted_at       TIMESTAMPTZ NOT NULL,        -- outgoing: our acceptance; incoming: gateway receipt
  send_by           TIMESTAMPTZ,                 -- outgoing
  deadline_at       TIMESTAMPTZ,                 -- outgoing 20 s deadline
  answer_deadline   TIMESTAMPTZ,                 -- incoming
  verdict           VARCHAR(6) CHECK (verdict IN ('ACCEPT','REJECT')),   -- incoming
  verdict_reason    VARCHAR(4),
  csm_outcome       VARCHAR(10) CHECK (csm_outcome IN ('SETTLED','REJECTED','NOT_SENT')),
  csm_outcome_at    TIMESTAMPTZ,
  booking_entry_id  UUID,
  pending_action    VARCHAR(30),
  next_action_at    TIMESTAMPTZ,
  action_attempts   INT NOT NULL DEFAULT 0,
  final_event_emitted BOOLEAN NOT NULL DEFAULT false,
  finalised_at      TIMESTAMPTZ,
  version           BIGINT NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  UNIQUE (direction, debtor_agent_bic, tx_id)          -- ADR-010
);
CREATE INDEX payments_due_action ON payments (next_action_at) WHERE pending_action IS NOT NULL;

CREATE TABLE payment_transitions (
  id BIGSERIAL PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payments,
  from_state VARCHAR(20), to_state VARCHAR(20) NOT NULL, reason_code VARCHAR(4),
  cause VARCHAR(50) NOT NULL,          -- e.g. RISK_ALLOW, CSM_SETTLED, DEADLINE, OPERATOR
  actor VARCHAR(100) NOT NULL DEFAULT 'system', at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_timers (
  timer_id   BIGSERIAL PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments,
  type       VARCHAR(30) NOT NULL CHECK (type IN ('OUTGOING_DEADLINE','SETTLEMENT_OVERDUE','INVESTIGATION_RETRY')),
  due_at     TIMESTAMPTZ NOT NULL,
  attempt    INT NOT NULL DEFAULT 0,
  status     VARCHAR(10) NOT NULL CHECK (status IN ('SCHEDULED','FIRED','CANCELLED')),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_timers_due ON payment_timers (due_at) WHERE status = 'SCHEDULED';

CREATE TABLE exception_cases (
  case_id    UUID PRIMARY KEY, payment_id UUID REFERENCES payments,
  type       VARCHAR(40) NOT NULL, status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
  details    JSONB NOT NULL, opened_at TIMESTAMPTZ NOT NULL,
  resolution VARCHAR(30), resolved_by VARCHAR(100), resolved_at TIMESTAMPTZ, comment VARCHAR(1000)
);
CREATE UNIQUE INDEX exception_cases_one_open ON exception_cases (payment_id, type) WHERE status = 'OPEN';
```

Plus `outbox`, `inbox`. Payments are retained (no deletion in the MVP); `command_id` uniqueness therefore holds
beyond the 24 h minimum of UC-01.

## 6. Behaviour

### 6.1 States

Outgoing: `RECEIVED → RISK_CHECKED → FUNDS_RESERVED → SENT_TO_CSM → SETTLED`, plus `INVESTIGATING`, `REJECTED`.
On-us: `FUNDS_RESERVED → SETTLED` directly.
Incoming: `RECEIVED → ACCEPT_REQUESTED → ACCEPTED → SETTLED`, plus `INVESTIGATING`, `REJECTED`.
`SETTLED` and `REJECTED` are final. A final state is never left; contradicting facts open a case.

### 6.2 Pending actions

A payment's next remote step is stored as `pending_action`. Every step follows **record → act → transition**:
the transaction that enters a state also sets the next `pending_action`; the runner performs the remote call
outside any transaction; a second transaction applies the result (with optimistic lock) and sets the next action.
This is what makes the saga resumable after a crash (ADR-003).

| `pending_action` | Remote call | On success | On definitive refusal | On technical failure |
|---|---|---|---|---|
| `ASSESS_RISK` (outgoing) | risk `POST /v1/risk/assessments` direction `OUTGOING` | `ALLOW` → `RISK_CHECKED`, next `CREATE_HOLD` | `DENY`/`REVIEW` → `REJECTED` `FR01`/`RR04` (UC-04 2a, 2b) | timeout 500 ms, retry once, then `REJECTED FR01` (2c, fail closed) |
| `CREATE_HOLD` | ledger create hold | `FUNDS_RESERVED`; next `ASSESS_PAYEE_RISK` if on-us, else `SEND` | `REJECTED` with mapped reason, no release needed (3a, 3b) | retry with back-off until `send_by − hold-give-up-margin`, then `REJECTED MS03`, next `RELEASE_HOLD` |
| `SEND` (local, no remote call) | — | if `now < send_by`: outbox `SendCreditTransfer`, `SENT_TO_CSM`, no next action | if `now ≥ send_by`: `REJECTED AB03`, next `RELEASE_HOLD` | — |
| `ASSESS_PAYEE_RISK` (on-us) | risk, direction `INCOMING`, same payment | next `SETTLE_ON_US` | `REJECTED FR01`/`RR04`, next `RELEASE_HOLD` (4a-1) | as `ASSESS_RISK`, then `REJECTED FR01`, next `RELEASE_HOLD` |
| `SETTLE_ON_US` | ledger settle-on-us | `SETTLED`, emit `PaymentSettled` | `REJECTED` `AC01`/`AC04`/`AC06`, next `RELEASE_HOLD` (4a-1) | retry until definitive answer; `LEDGER_ACTION_STUCK` case after `stuck-after` |
| `SETTLE_HOLD` | ledger settle | `SETTLED`, emit `PaymentSettled` | `HOLD_RELEASED` → `CONTRADICTING_CSM_OUTCOME` case | retry forever, capped back-off; case after `stuck-after` |
| `RELEASE_HOLD` | ledger release | emit `PaymentRejected`, no next action | `HOLD_SETTLED` → `CONTRADICTING_CSM_OUTCOME` case | retry forever; case after `stuck-after` |
| `ASSESS_RISK` (incoming) | risk direction `INCOMING` | next `CREATE_PENDING_CREDIT` | verdict `REJECT` `FR01`/`RR04` (UC-06 3a) | timeout 300 ms, retry once, then verdict `REJECT FR01` |
| `CREATE_PENDING_CREDIT` | ledger create pending credit (id = incoming `paymentId`, ADR-010) | outbox `AcceptCreditTransfer`, `ACCEPT_REQUESTED` | verdict `REJECT` with mapped reason (4a) | retry until `answer_deadline − verdict-give-up-margin`, then verdict `REJECT MS03` |
| `CONFIRM_CREDIT` | ledger confirm | `SETTLED`, emit `PaymentSettled` | `PENDING_CREDIT_CANCELLED` → contradiction case | retry forever; case after `stuck-after` |
| `CANCEL_CREDIT` | ledger cancel | emit `PaymentRejected` | `PENDING_CREDIT_CONFIRMED` → contradiction case | retry forever; case after `stuck-after` |

"Verdict `REJECT`" means: in one transaction set `verdict = REJECT`, `verdict_reason`, write outbox
`RejectCreditTransfer`; the state stays `RECEIVED` until `AnswerSent` arrives (ADR-003: `REJECTED` follows the
answer actually sent).

Ledger error mapping: `ACCOUNT_NOT_FOUND → AC01`, `ACCOUNT_CLOSED → AC04`, `ACCOUNT_BLOCKED → AC06`,
`ACCOUNT_NOT_CUSTOMER → AG01`, `INSUFFICIENT_FUNDS → AM04`, `CUSTOMER_ID_MISSING → MS03` (technical reject, Q-02),
anything else `→ MS03`.

Rules:

- When a rejection happens *after* a `CREATE_HOLD` call may have been issued, the next action is always
  `RELEASE_HOLD` (the ledger tombstone makes this safe even if the hold was never created). Likewise every incoming
  rejection after a possible `CREATE_PENDING_CREDIT` ends in `CANCEL_CREDIT`.
- A step result is applied only if the payment is still in the state and `pending_action` the step was started for;
  otherwise it is discarded (an event or timer overtook it). Discarding is safe because the overtaking transition
  set its own compensating action.
- Final events: `PaymentSettled` / `PaymentRejected` are written to the outbox in the transaction that completes
  the ledger effect (or in the rejecting transaction if no ledger effect is needed), and `final_event_emitted` is set.
  Exactly one final event per payment.

### 6.3 Saga runner

- After a transaction that sets `pending_action` commits, a `TransactionSynchronization.afterCommit` hook submits
  the payment id to the runner executor (virtual threads, bounded by a semaphore of `runner.max-concurrency`).
- **Claim:** `UPDATE payments SET next_action_at = now() + :lease, action_attempts = action_attempts + 1
  WHERE payment_id = :id AND pending_action = :action AND next_action_at <= now()` — 0 rows → someone else has it.
- **Sweeper:** a `PollingWorker` (100 ms) picks payments with `pending_action IS NOT NULL AND next_action_at <=
  now()` (expired lease or scheduled retry) and hands them to the runner. This recovers actions lost in a crash.
- On technical failure the runner sets `next_action_at = now() + backoff(action_attempts)` (100 ms, 200 ms, 400 ms
  … capped at `max-backoff`) unless the table above says to give up.
- Every remote call's read timeout is `min(configured timeout, deadline − now − safety-margin)` where the deadline is
  `send_by` (outgoing before sending), `answer_deadline` (incoming before the verdict), or none (settle / release /
  confirm / cancel).

### 6.4 Timers

Handled by a `PollingWorker` over `payment_timers` (100 ms). Handlers only change local state and write the outbox.

| Timer | Created | When it fires |
|---|---|---|
| `OUTGOING_DEADLINE` at `deadline_at` | with the payment | final state or `csm_outcome` set → no-op. `RECEIVED` → `REJECTED AB03`, emit `PaymentRejected`. `RISK_CHECKED`, or `FUNDS_RESERVED` (not on-us) → `REJECTED AB03`, next `RELEASE_HOLD`. On-us in `FUNDS_RESERVED` → no-op (no scheme deadline without a CSM; its actions retry to a definitive answer). `SENT_TO_CSM` → `INVESTIGATING`, outbox `RequestPaymentStatus(attempt 1)`, schedule `INVESTIGATION_RETRY` (UC-05 1). |
| `SETTLEMENT_OVERDUE` at `AnswerSent(ACCP).sentAt + settlement-overdue` | on `AnswerSent(ACCP)` | if still `ACCEPTED` without `csm_outcome` → `INVESTIGATING`, `RequestPaymentStatus(INCOMING, 1)`, schedule retry (UC-06 8b). |
| `INVESTIGATION_RETRY` | after each status request | if still `INVESTIGATING` without outcome: next attempt per `investigation.backoff`; when `now − first request > investigation.budget` → open `UNRESOLVED_INVESTIGATION`, no further automatic retries (UC-05 4a, 4b). |

Recording a CSM outcome cancels all `SCHEDULED` timers of the payment.

### 6.5 CSM event handling

Listener transaction: validate → inbox → load payment by `(direction, debtorAgentBic, txId)` → apply:

| Event | Payment state | Effect |
|---|---|---|
| `CreditTransferReceived` | none | create incoming payment `RECEIVED`, `accepted_at = receivedAt`, `answer_deadline`, next `ASSESS_RISK` (UC-06 2) |
| | exists | no-op (duplicate with new dedup key — should not happen) |
| `AnswerSent(ACCP)` | `ACCEPT_REQUESTED` | `ACCEPTED`, schedule `SETTLEMENT_OVERDUE` (UC-06 7) |
| | other | `CONTRADICTING_CSM_OUTCOME` case (we never sent an accept verdict) |
| `AnswerSent(RJCT)` | `RECEIVED`, `ACCEPT_REQUESTED` | `REJECTED` with the sent reason, next `CANCEL_CREDIT` (UC-06 6a, 6b) |
| `CreditTransferSettled` OUTGOING | `SENT_TO_CSM`, `INVESTIGATING` | `csm_outcome = SETTLED`, next `SETTLE_HOLD` (UC-04 8, UC-05 6) |
| | `SETTLED` | no-op |
| | `REJECTED` | `CONTRADICTING_CSM_OUTCOME` case (UC-04 8a) |
| `CreditTransferRejected` OUTGOING | `SENT_TO_CSM`, `INVESTIGATING` | `REJECTED` with received reason, next `RELEASE_HOLD` (UC-04 6a) |
| | `REJECTED` | no-op · `SETTLED` → contradiction case |
| `CreditTransferNotSent` | `SENT_TO_CSM`, `INVESTIGATING` | `REJECTED AB03`, next `RELEASE_HOLD` (UC-04 5a, UC-05 2a) |
| `CreditTransferSettled` INCOMING | `ACCEPTED`, `INVESTIGATING`, `ACCEPT_REQUESTED` | `csm_outcome = SETTLED`, next `CONFIRM_CREDIT` (UC-06 9) |
| | `REJECTED` | contradiction case |
| `CreditTransferRejected` INCOMING | `ACCEPTED`, `INVESTIGATING`, `ACCEPT_REQUESTED` | `REJECTED` with reason, next `CANCEL_CREDIT` (UC-06 8a) |
| any OUTGOING event | `RECEIVED`, `RISK_CHECKED`, `FUNDS_RESERVED` | contradiction case (gateway cannot know the payment yet) |
| any event for unknown `txId` | — | `DLT_MESSAGE`-style case with no payment; message acknowledged |

### 6.6 Concurrency

- `payments.version` optimistic lock on every update. A conflicting listener transaction is retried by the
  container (retryable error); a conflicting runner result transaction reloads and re-checks the "still applicable"
  rule.
- Timer handlers and listeners may race on the same payment; the optimistic lock serialises them and the state
  tables above are written so that either order yields a consistent result.

## 7. Configuration

| Property | Default | Notes |
|---|---|---|
| `tpe.outgoing.send-by-offset` | `10s` | startup check: `> hold-give-up-margin` and `< deadline-offset` |
| `tpe.outgoing.deadline-offset` | `20s` | scheme time-out deadline (A5) |
| `tpe.outgoing.hold-give-up-margin` | `2s` | before `send_by` |
| `tpe.incoming.verdict-give-up-margin` | `1s` | before `answer_deadline` |
| `tpe.incoming.settlement-overdue` | `30s` | see [Q-06](40-open-questions.md) |
| `tpe.investigation.backoff` | `5s,15s,60s,300s` | last value repeats |
| `tpe.investigation.budget` | `1h` | |
| `tpe.runner.lease` | `5s` | |
| `tpe.runner.max-backoff` | `30s` | |
| `tpe.runner.max-concurrency` | `256` | |
| `tpe.runner.stuck-after` | `10m` | `LEDGER_ACTION_STUCK` |
| `tpe.clients.risk.timeout` | `500ms` outgoing / `300ms` incoming | |
| `tpe.clients.ledger.timeout` | `1s` | |
| `tpe.clients.*.base-url` | compose hosts | |

## 8. Non-functional requirements

- Outgoing: `accepted_at` → `SendCreditTransfer` in outbox ≤ 1 s p99 (UC-04 internal budget, steps 2–5 minus gateway).
- Incoming: `CreditTransferReceived` consumed → verdict in outbox ≤ 1.2 s p99 (leaves headroom in the 2 s budget of UC-06).
- Horizontally scalable: no in-memory state that matters; any instance can run any action or timer.
- Metrics: `tpe.payment.duration{direction,outcome}` (accepted → final), `tpe.saga.action.duration{action,result}`,
  `tpe.payment.state{direction,state}` (gauge of non-final payments), `tpe.timer.fired{type}`,
  `tpe.exception.cases.open{type}`, `tpe.deadline.reject{reason}`, plus `tpe.payment.stage.duration` and
  `tpe.payment.rejected` ([25 §11](25-service-levels.md#11-metrics-to-add)).
- Service levels: SLO-ORC-01 … -10 and OLA-ORC-01 in [25 §5.2](25-service-levels.md#52-payment-orchestrator).

## 9. Acceptance criteria

- **ORC-AC-01** Submitting the same `commandId` twice returns the same `paymentId` (`201` then `200`) and creates one
  payment. (UC-01 6a)
- **ORC-AC-02** Happy path outgoing: states `RECEIVED → RISK_CHECKED → FUNDS_RESERVED → SENT_TO_CSM → SETTLED`, one
  `SendCreditTransfer` with `sendBy = acceptedAt + 10s`, one `PaymentSettled`. (UC-04)
- **ORC-AC-03** Risk `DENY` → `REJECTED FR01`, no ledger call, `PaymentRejected` emitted. (UC-04 2a)
- **ORC-AC-04** Risk timeout twice → `REJECTED FR01`. (UC-04 2c)
- **ORC-AC-05** `INSUFFICIENT_FUNDS` → `REJECTED AM04`, no release call. (UC-04 3a)
- **ORC-AC-06** If `SEND` runs after `send_by` (clock advanced), no `SendCreditTransfer` is written; payment
  `REJECTED AB03`, hold released.
- **ORC-AC-07** `CreditTransferNotSent` → `REJECTED AB03`, hold released, `PaymentRejected`. (UC-04 5a)
- **ORC-AC-08** `CreditTransferRejected AC04` → hold released, `REJECTED AC04`. (UC-04 6a)
- **ORC-AC-09** Deadline in `SENT_TO_CSM` → `INVESTIGATING`, `RequestPaymentStatus` attempt 1, retries at +5 s, +15 s,
  +60 s, then every 5 min; after 1 h an `UNRESOLVED_INVESTIGATION` case; hold never released meanwhile. (UC-05)
- **ORC-AC-10** `CreditTransferSettled` after `REJECTED` opens a `CONTRADICTING_CSM_OUTCOME` case and makes no ledger
  call. (UC-04 8a)
- **ORC-AC-11** On-us happy path: payee risk assessed, single `settle-on-us`, `SETTLED`, no Kafka command, one
  `PaymentSettled` with `onUs = true`. (UC-04 4a)
- **ORC-AC-12** On-us with blocked payee → `REJECTED AC06` and hold released. (UC-04 4a-1)
- **ORC-AC-13** Kill the instance after the ledger call of each action, restart: the payment reaches the same final
  state with the same ledger effect (parameterised test over every `pending_action`). (ADR-003)
- **ORC-AC-14** Incoming happy path: `RECEIVED → ACCEPT_REQUESTED → ACCEPTED → SETTLED`; `ACCEPTED` only after
  `AnswerSent(ACCP)`. (UC-06, ADR-004)
- **ORC-AC-15** Incoming risk `DENY` → `RejectCreditTransfer FR01`, state stays `RECEIVED` until `AnswerSent(RJCT)`,
  then `REJECTED`, `CANCEL_CREDIT` issued. (UC-06 3a)
- **ORC-AC-16** `AnswerSent(RJCT AB05)` while `CREATE_PENDING_CREDIT` is in flight: result discarded, pending credit
  cancelled (or tombstoned), payment `REJECTED AB05`. (UC-06 6b)
- **ORC-AC-17** Incoming no settlement within 30 s of `AnswerSent(ACCP)` → `INVESTIGATING`, `RequestPaymentStatus`
  with `direction = INCOMING`. (UC-06 8b)
- **ORC-AC-18** Duplicate CSM event with a new `eventId` but same `txId`/type/outcome has no effect. (ADR-002)
- **ORC-AC-19** Exactly one of `PaymentSettled` / `PaymentRejected` per payment across all scenarios of the
  [scenario catalogue](20-testing-strategy.md#4-end-to-end-scenario-catalogue).
- **ORC-AC-20** Resolving an `UNRESOLVED_INVESTIGATION` as `CONFIRMED_REJECTED` releases the hold and records the
  operator in `payment_transitions`.
- **ORC-AC-21** Risk `REVIEW` → `REJECTED FR01`, no ledger call, `PaymentRejected` emitted. (UC-04 2b)
- **ORC-AC-22** Ledger create-hold failing technically (WireMock `503`) until `send_by − hold-give-up-margin` →
  `REJECTED MS03`, next `RELEASE_HOLD`; the release is issued (tombstone if the hold never existed) and one
  `PaymentRejected` is emitted. (UC-04 3, Q-01, ADR-009)
- **ORC-AC-23** On-us payment in `FUNDS_RESERVED` whose `settle-on-us` call is still in flight when the clock passes
  `deadline_at`: the `OUTGOING_DEADLINE` timer is a no-op and the payment ends `SETTLED` on the ledger answer, with no
  `RELEASE_HOLD`. (Q-15, ADR-008)
- **ORC-AC-24** `CreditTransferSettled` (source `STATUS_REPORT`) while `INVESTIGATING` → `SETTLE_HOLD`, `SETTLED`, the
  `INVESTIGATION_RETRY` timer cancelled; a following `CreditTransferSettled` (source `INVESTIGATION`) for the same
  transaction has no effect: one settle call, one `PaymentSettled`. (UC-05, late `pacs.002` during investigation)
- **ORC-AC-25** Incoming `CreditTransferRejected` in `ACCEPTED` → `REJECTED` with the received reason, next
  `CANCEL_CREDIT`, the pending credit cancelled, one `PaymentRejected`, `SETTLEMENT_OVERDUE` timer cancelled. (UC-06 8a)
- **ORC-AC-26** Two `CreditTransferReceived` with the same `txId` and different `debtorAgentBic` create two payments
  with different `paymentId`s and pending-credit ids; neither is deduplicated against the other, and events for one
  never change the other. (ADR-010)
