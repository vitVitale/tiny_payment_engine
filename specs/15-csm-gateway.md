# 15 csm-gateway

Architecture: [C4 components](../architecture/c4/03-components-csm-gateway.puml),
[ADR-004](../architecture/decisions/ADR-004-csm-gateway-owns-scheme-timing.md),
[UC-04](../architecture/use-cases/UC-04-execute-outgoing-payment.md) 5–7, [UC-05](../architecture/use-cases/UC-05-resolve-missing-csm-response.md) 2–5,
[UC-06](../architecture/use-cases/UC-06-receive-incoming-payment.md) 1, 6, 8.

## 1. Responsibilities

- The **only** component that decides what is sent to the CSM and when.
- Outgoing: send-by guard, build / validate / journal / transmit `pacs.008`, status requests (`pacs.028`).
- Incoming: validate and deduplicate `pacs.008`, record the answer deadline, send **exactly one** answer per `TxId`.
- Correlate every `pacs.002` with the original transaction and publish facts to `csm.inbound.events`.
- Journal every message in and out.

Not responsible for: payment state, risk, ledger, choosing the verdict (orchestrator).

## 2. Module layout

| Package | C4 component |
|---|---|
| `api.commands` | Command listener |
| `api.csm` | CSM inbound endpoint |
| `application.outgoing` | Outgoing payment handler (send-by guard), the outgoing events |
| `application.investigation` | `RequestPaymentStatus` and the `pacs.028` it sends (§6.6) |
| `application.incoming` | Incoming payment handler |
| `application.answer` | Answer coordinator |
| `application.correlation` | Correlation service |
| `application.transmit` | Transmitter (§6.5) |
| `infrastructure.transmit` | Transmit worker: polls the transmitter (§6.5) |
| `infrastructure.timers` | Answer deadline scheduler |
| `infrastructure.journal` | Message journal repositories |
| `infrastructure.csm` | CSM client (`RestClient`, mTLS in `prod`) |
| `infrastructure.messaging` | outbox / listener wiring |

ISO 20022 codec and builders come from `iso20022-model`.

## 3. Interfaces

### 3.1 CSM transport (simulated)

| Direction | Endpoint | Body |
|---|---|---|
| gateway → CSM | `POST {tpe.csm.base-url}/csm/v1/messages` | envelope with `pacs.008`, `pacs.002` or `pacs.028` |
| CSM → gateway | `POST /csm/v1/inbound` | envelope with `pacs.008` or `pacs.002` |

`Content-Type: application/xml`. The HTTP response is a transport acknowledgement only: `202 Accepted` once the
message is journalled (inbound) / accepted by the CSM (outbound); business answers always come back as separate
messages. `400` only if the envelope cannot be parsed at all, or no `TxId` (with its `DbtrAgt` BIC) can be extracted
from a `pacs.008` (§6.2); nothing is journalled then. In `prod` the
connection uses mTLS; `local` / `test` use plain HTTP.

### 3.2 Admin API (role `BACKOFFICE_OPERATOR`, read-only)

- `GET /v1/journal?txId=…[&debtorAgentBic=…]` — all journalled messages for a `TxId` (XML, direction, debtor agent BIC,
  timestamps, transport status); `debtorAgentBic` narrows it to one transaction.
- `GET /v1/transactions/{txId}[?debtorAgentBic=…]` — every outgoing and incoming transaction record with that `TxId`
  (§5), each with its `debtorAgentBic`, the outgoing one first; an incoming one carries its answer (`answerStatus`,
  `answerReason`, `answerDecidedBy`, `answeredAt`, `answerMsgId`) and the answer's transmit status. A `TxId` alone is
  not an identity: two Originator PSPs may send the same one
  ([ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md)).

## 4. Messaging

| Direction | Topic | Messages | Dedup |
|---|---|---|---|
| consume | `csm.outbound.commands` | `SendCreditTransfer`, `RequestPaymentStatus`, `AcceptCreditTransfer`, `RejectCreditTransfer` | `<type>:<debtorAgentBic>:<txId>[:<attempt>]` ([02 §3](02-messaging-contracts.md#3-csmoutboundcommands-orchestrator--gateway), ADR-010) |
| produce | `csm.inbound.events` | `CreditTransferReceived`, `AnswerSent`, `CreditTransferSettled`, `CreditTransferRejected`, `CreditTransferNotSent` | — |

A gateway that dies without leaving its consumer group (`SIGKILL`, OOM, node loss) holds its partitions of
`csm.outbound.commands`, and the verdicts on them, until its consumer's session ends; so the session is shorter than
the answer deadline (§6.2, §7): 3 s with a 1 s heartbeat (1.5 s / 500 ms in the `systemtest` profile, whose deadline
is 2 s), and the gateway does not start otherwise. Brokers allow it ([02 §2](02-messaging-contracts.md#2-topics)).
A verdict waits for the session at most once: with the orchestrator's verdict within 1.2 s
([25 §3.3](25-service-levels.md#33-incoming-latency-budget-answer-deadline-5-s-verdict-give-up-4-s)), the moved
partition is consumed before the 5 s deadline; the `systemtest` profile keeps the order, not that margin, and no
system test kills a gateway under incoming load. A gateway paused longer than its session (a long GC pause,
E2E-O-12's freeze, CH-13's `docker pause`) leaves the group and rejoins; its uncommitted commands are consumed again,
which the inbox deduplicates. Static membership and the KIP-848 protocol do not help: both detect a dead member by
its session as well ([#158](https://github.com/vitVitale/tiny_payment_engine/issues/158)).

## 5. Data model

```sql
CREATE TABLE outgoing_transfers (
  tx_id            VARCHAR(35) PRIMARY KEY,
  payment_id       UUID NOT NULL,
  uetr             UUID NOT NULL,
  msg_id           VARCHAR(35) NOT NULL UNIQUE,
  accepted_at      TIMESTAMPTZ NOT NULL,
  send_by          TIMESTAMPTZ NOT NULL,
  deadline_at      TIMESTAMPTZ NOT NULL,
  send_status      VARCHAR(10) NOT NULL CHECK (send_status IN ('SENT','NOT_SENT')),
  not_sent_reason  VARCHAR(4),
  journalled_at    TIMESTAMPTZ NOT NULL,
  transmit_status  VARCHAR(10) CHECK (transmit_status IN ('PENDING','DELIVERED','GAVE_UP')),
  transmit_attempts INT NOT NULL DEFAULT 0,
  next_transmit_at TIMESTAMPTZ,
  delivered_at     TIMESTAMPTZ,
  final_status     VARCHAR(10) CHECK (final_status IN ('SETTLED','REJECTED')),
  final_reason     VARCHAR(35),
  final_at         TIMESTAMPTZ,
  final_source     VARCHAR(13) CHECK (final_source IN ('STATUS_REPORT','INVESTIGATION')), -- re-published as it was (§6.6)
  trace_context    VARCHAR(55),                  -- W3C traceparent of the payment trace (ADR-014)
  version          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE incoming_transfers (
  debtor_agent_bic VARCHAR(11) NOT NULL,          -- ADR-010: identity = (debtor_agent_bic, tx_id)
  tx_id            VARCHAR(35) NOT NULL,
  msg_id           VARCHAR(35) NOT NULL,
  received_at      TIMESTAMPTZ NOT NULL,
  answer_deadline  TIMESTAMPTZ NOT NULL,
  answer_status    VARCHAR(4) CHECK (answer_status IN ('ACCP','RJCT')),
  answer_reason    VARCHAR(4),
  answer_decided_by VARCHAR(10) CHECK (answer_decided_by IN ('VERDICT','DEADLINE','VALIDATION')),
  answered_at      TIMESTAMPTZ,
  answer_msg_id    VARCHAR(35) UNIQUE,
  transmit_status  VARCHAR(10) CHECK (transmit_status IN ('PENDING','DELIVERED','GAVE_UP')),
  transmit_attempts INT NOT NULL DEFAULT 0,
  next_transmit_at TIMESTAMPTZ,
  final_status     VARCHAR(10) CHECK (final_status IN ('SETTLED','REJECTED')),
  final_reason     VARCHAR(35),
  final_at         TIMESTAMPTZ,
  final_source     VARCHAR(13) CHECK (final_source IN ('STATUS_REPORT','INVESTIGATION')), -- re-published as it was (§6.6)
  trace_context    VARCHAR(55),                  -- root of the incoming payment trace (ADR-014)
  version          BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (debtor_agent_bic, tx_id)
);
CREATE INDEX incoming_unanswered ON incoming_transfers (answer_deadline) WHERE answer_status IS NULL;
CREATE INDEX incoming_tx ON incoming_transfers (tx_id);               -- the admin API, across debtor agents
CREATE INDEX incoming_msg_id ON incoming_transfers (msg_id);          -- a pacs.002 without OrgnlTxRef/DbtrAgt (§6.4)
CREATE INDEX incoming_transmit_due ON incoming_transfers (next_transmit_at) WHERE transmit_status = 'PENDING';

CREATE TABLE status_requests (
  debtor_agent_bic VARCHAR(11) NOT NULL,          -- ADR-010; our own BIC for outgoing transfers
  tx_id VARCHAR(35) NOT NULL, direction VARCHAR(8) NOT NULL, attempt INT NOT NULL,
  msg_id VARCHAR(35) NOT NULL UNIQUE, created_at TIMESTAMPTZ NOT NULL,
  transmit_status VARCHAR(10) NOT NULL, transmit_attempts INT NOT NULL DEFAULT 0, next_transmit_at TIMESTAMPTZ,
  PRIMARY KEY (debtor_agent_bic, tx_id, direction, attempt)
);

CREATE TABLE message_journal (
  id          BIGSERIAL PRIMARY KEY,
  direction   VARCHAR(3) NOT NULL CHECK (direction IN ('IN','OUT')),
  message_type VARCHAR(20) NOT NULL,          -- pacs.008.001.08, …
  msg_id      VARCHAR(35),
  tx_id       VARCHAR(35),
  debtor_agent_bic VARCHAR(11),               -- with tx_id the transaction identity (ADR-010); NULL if unreadable
  xml         TEXT NOT NULL,
  valid       BOOLEAN NOT NULL,
  validation_errors JSONB,
  note        VARCHAR(200),                   -- e.g. 'late verdict ignored', 'duplicate pacs.008 dropped'
  at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX message_journal_tx ON message_journal (tx_id, id);
```

Plus `outbox`, `inbox`. The journal is append-only and retained 10 years in production (regulatory record
keeping); in the MVP no deletion job exists.

## 6. Behaviour

All decisions use the injected `Clock`; SQL compares against a `:now` parameter from that clock, never `now()`,
so tests can control time and a single time source decides.

### 6.1 Outgoing: `SendCreditTransfer`

Listener transaction:

1. Inbox. If `outgoing_transfers` already has the `txId` → no-op.
2. If `now ≥ sendBy`: insert row `send_status = NOT_SENT`, `not_sent_reason = AB03`; outbox `CreditTransferNotSent`. Done.
3. Build `pacs.008` + BAH with a new `MsgId`, `AccptncDtTm = acceptedAt`, `IntrBkSttlmDt` = UTC calendar date of `now`
   (SCT Inst settles 24/7; the real CSM business-day cut-over is out of scope, see [Q-12](40-open-questions.md)),
   validate against XSD. Invalid → insert `NOT_SENT` with reason `FF01`, journal with `valid = false`, outbox
   `CreditTransferNotSent(FF01)`, alert (this is a bug). Done.
4. Insert row `send_status = SENT`, `transmit_status = PENDING`, `next_transmit_at = now`; journal `OUT`. Commit.
   The `pacs.008` is now *journalled as sent* (ADR-004) — the decision is final.
5. Transmitter sends it (§6.5).

Transmit rules for `pacs.008`:

- Before **every** attempt (including the first) check `now < sendBy`; otherwise stop with `GAVE_UP`
  ([ADR-011](../architecture/decisions/ADR-011-send-by-guard-per-transmission.md)).
- If the row reaches `GAVE_UP` with `transmit_attempts = 0` (no byte ever left, e.g. the gateway was down between
  commit and transmit), it is provably not sent: switch `send_status` to `NOT_SENT` (`AB03`) and publish
  `CreditTransferNotSent` in the same transaction. With `transmit_attempts ≥ 1` the status stays `SENT` — the CSM may
  have it; the investigation resolves it.
- Retries reuse the same `MsgId`; the CSM deduplicates by `MsgId`.

### 6.2 Incoming: `pacs.008`

Endpoint, one transaction:

1. Validate envelope + document against XSD and check `CdtrAgt` BIC = our BIC (and `InstdAgt`, where given),
   `IntrBkSttlmCcy = EUR`, `NbOfTxs = 1`, and that every field the event carries is a valid domain value. Journal `IN`
   with the result (the violation codes in `validation_errors`).
2. Invalid but `TxId` and `DbtrAgt` BIC readable → create `incoming_transfers` row with `answer_status = RJCT`,
   `answer_reason = FF01`, `answer_decided_by = VALIDATION`, `answered_at = received_at = now`, `answer_deadline` as
   in 4, transmit `PENDING`; build, journal `OUT` and transmit the answer `pacs.002` (from our BIC to `tpe.csm.bic`,
   `OrgnlTxRef/DbtrAgt`; `OrgnlMsgId` `NOTPROVIDED` if the `MsgId` cannot be read). No event — no payment is created
   (UC-06 1a). The identity of an invalid message is read without a DTD: a DOCTYPE is skipped, never processed, and an
   entity is never resolved (GW-AC-14); reading stops at the first error, keeping what came before. With no readable
   `TxId` and `DbtrAgt` BIC there is nothing to answer: `400`, nothing journalled (§3.1).
3. Duplicate (`DbtrAgt` BIC, `TxId`) (row exists), valid or not: if an answer was decided → reset
   `transmit_status = PENDING` to re-send the same answer `pacs.002` (same `answer_msg_id`), within its give-up window
   (§6.5); else drop. Journal note either way; no event (UC-06 1b).
4. New: insert row with `received_at = now`, `answer_deadline = now + answer-deadline-offset`, `trace_context` = the
   request's server span, the root of the incoming payment's trace (ADR-014); outbox `CreditTransferReceived`.
   Commit → `202`.

### 6.3 Answer coordinator (exactly one answer per `TxId`)

Two writers compete for the same row; both use a conditional update, so exactly one wins:

- **Verdict** (`AcceptCreditTransfer` / `RejectCreditTransfer`, listener transaction):

  ```sql
  UPDATE incoming_transfers
     SET answer_status = :status, answer_reason = :reason, answer_decided_by = 'VERDICT',
         answered_at = :now, answer_msg_id = :msgId, transmit_status = 'PENDING', next_transmit_at = :now,
         version = version + 1
   WHERE debtor_agent_bic = :debtorAgentBic AND tx_id = :txId
     AND answer_status IS NULL AND answer_deadline > :now
  ```

  1 row → build `pacs.002` (`ACCP`, or `RJCT` + reason), journal, outbox `AnswerSent(status, reason, VERDICT)`.
  0 rows → late or second verdict, or one for no known transfer: journal note `verdict ignored` (a row with the
  command type as `message_type`, the transaction's `tx_id` / `debtor_agent_bic` and an empty `xml`: the verdict is
  not a CSM message), metric `tpe.csm.verdict.ignored`, no event. A redelivered verdict command is dropped by the
  inbox (`<type>:<debtorAgentBic>:<txId>`, 02 §3) before any of this.
- **Deadline** (`PollingWorker`, 50 ms, `WHERE answer_status IS NULL AND answer_deadline <= :now FOR UPDATE SKIP
  LOCKED`): set `RJCT`, `AB05`, `DEADLINE` with the same conditional update but `answer_deadline <= :now`, build /
  journal `pacs.002`, outbox `AnswerSent(RJCT, AB05, DEADLINE)` (UC-06 6b), in a `csm.deadline.answer` span on the
  payment's trace.

The answer `pacs.002` goes from our BIC to `tpe.csm.bic` with `OrgnlTxRef/DbtrAgt`, the `pacs.008`'s `MsgId`, and
its `EndToEndId` and `UETR` read from the journalled `pacs.008`. `AnswerSent.sentAt` is `answered_at`, the moment the
answer was decided; `tpe.csm.answer.margin` (a timer) records `answer_deadline − answered_at`; a negative margin, a
deadline answer decided a poll after the deadline, is not recorded.

`AnswerSent` is published when the answer is decided and journalled, in the same transaction; transmission follows.
If transmission ultimately fails, the CSM times the payment out and later reports it rejected (UC-06 8a), which the
orchestrator already handles.

### 6.4 Correlation: inbound `pacs.002`

Endpoint transaction: validate, journal, then match `OrgnlTxId` + `OrgnlTxRef/DbtrAgt` BIC (fallback: the journalled
`OrgnlMsgId`) — [ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md):

| Match | `TxSts` | Effect |
|---|---|---|
| `outgoing_transfers` | `ACSC` | first final status → set `SETTLED`, outbox `CreditTransferSettled(OUTGOING)` |
| | `RJCT` + reason | first final status → set `REJECTED`, outbox `CreditTransferRejected(OUTGOING, reason)` |
| | `RJCT` + `UNKNOWN_TX` (answer to `pacs.028`) | if `send_status = SENT`, no final status yet and `now ≥ deadline_at` → `REJECTED AB05`, outbox `CreditTransferRejected(OUTGOING, AB05, INVESTIGATION)` (UC-05 4c); otherwise journal only (a late answer after a final status is a no-op, UC-05) |
| `incoming_transfers` | `ACSC` | set `SETTLED`, outbox `CreditTransferSettled(INCOMING)` (UC-06 8) |
| | `RJCT` | set `REJECTED`, outbox `CreditTransferRejected(INCOMING, reason)` (UC-06 8a) |
| | `ACCP`, `RJCT` + `UNKNOWN_TX` | journal only |
| none | any | journal with note `uncorrelated`, alert |

- Same final status again → journal only (duplicate).
- A *different* final status after one was recorded → journal, keep the first in the row, and publish the
  contradicting event anyway; the orchestrator opens a case (UC-04 8a). The `source` field is `STATUS_REPORT` or
  `INVESTIGATION` (if the `pacs.002` answers a `pacs.028` we sent, matched via `status_requests.msg_id`). Its
  `OrgnlMsgId` is then the `pacs.028`'s, so the `OrgnlMsgId` fallback also looks it up there.
- `ACCP` on an outgoing transfer is informational (beneficiary accepted, settlement pending) — journal only.

### 6.5 Transmitter

- One lease-based worker for `outgoing_transfers`, `incoming_transfers` (answers) and `status_requests` rows with
  `transmit_status = PENDING AND next_transmit_at <= :now`. Claim = conditional update of `next_transmit_at = now +
  lease` and `transmit_attempts + 1`; the HTTP call happens after the claim commits (no lock held during I/O);
  an `afterCommit` fast path transmits immediately. It is not a `PollingWorker`
  ([00 §10](00-engineering-baseline.md#10-persistent-timers-and-polling-workers)): that one holds the row lock while it
  handles a row, and the HTTP call must run without it. A poll sends its rows side by side, so one slow CSM call holds
  up no other row.
- `2xx` → `DELIVERED`. Error / timeout → `next_transmit_at = now + 200 ms × 2^(attempts−1)` capped at 1 s.
- Give-up windows: `pacs.008` at `sendBy` (§6.1); answers at `answer_deadline + answer-transmit-window`, for verdict and
  deadline answers alike (the CSM stops waiting for our answer shortly after the deadline, so later retries are only
  traffic); status requests
  after 5 attempts (the orchestrator's next retry sends a new one).
- Timeouts: connect 200 ms, read 1 s.

### 6.6 `RequestPaymentStatus`

Listener transaction; per direction:

| Outgoing record | Effect |
|---|---|
| none (the `SendCreditTransfer` never arrived, e.g. it went to the DLT) | outbox `CreditTransferNotSent(AB03)` — nothing was sent |
| `NOT_SENT` | outbox `CreditTransferNotSent` again (orchestrator dedups) (UC-05 2a) |
| final status known | re-publish the final event (orchestrator dedups) |
| `SENT`, no final status | insert `status_requests` row (`debtor_agent_bic` = own BIC), build `pacs.028` for `OrgnlTxId`, journal, transmit (UC-05 3) |

- Deduplicated per attempt (`RequestPaymentStatus:<debtorAgentBic>:<txId>:<attempt>`, 02 §3): a redelivered command
  sends no second `pacs.028`, the next attempt sends a new one with its own `MsgId`.
- "None" publishes `CreditTransferNotSent(AB03)` with `sendBy` = `decidedAt` = now (the gateway never saw the
  command's `sendBy`), and takes the `SendCreditTransfer`'s inbox key, so one arriving later (e.g. replayed from
  the DLT) is a duplicate and is never sent.
- A re-published `CreditTransferNotSent` has `decidedAt` = now; a re-published final event carries the recorded
  `final_at`, reason and `final_source`.
- The `pacs.028` carries the `pacs.008`'s `MsgId` (`OrgnlMsgId`), `TxId`, `EndToEndId` (read from the journalled
  `pacs.008`) and `UETR`, from our BIC to `tpe.csm.bic`. It is journalled `OUT` and transmitted like a `pacs.008`
  (§6.5), on the payment's trace.
Incoming: look up `incoming_transfers` by the command's (`debtorAgentBic`, `txId`); final status known → re-publish
(`direction = INCOMING`, no `paymentId`, the recorded `final_at`, reason and `final_source`); else insert a
`status_requests` row with that `debtor_agent_bic` and send a `pacs.028` (with `OrgnlTxRef/DbtrAgt`) as above, its
`EndToEndId` and `UETR` read from the journalled `pacs.008`, on the incoming payment's trace; no record (or no
journalled `pacs.008` to name it by) → journal note and alert (`tpe.csm.uncorrelated`, an `ERROR` log), nothing sent.

## 7. Configuration

| Property | Default |
|---|---|
| `tpe.csm.base-url` | `http://csm-simulator:8090` |
| `tpe.csm.bic` | `TIPSXXXXXXX` (the CSM's BIC: the instructed agent of a `pacs.028`) |
| `tpe.csm.answer-deadline-offset` | `5s` |
| `tpe.csm.answer-transmit-window` | `1s` (after `answer_deadline`; `answer-deadline-offset` + this ≤ the CSM's answer timeout, [20 §2.1](20-testing-strategy.md#21-systemtest-profile)) |
| `tpe.csm.transmit.lease` | `2s` |
| `tpe.csm.transmit.poll-interval` | `50ms` (how often the transmit worker looks for due and expired rows, §6.5) |
| `tpe.csm.timeouts.connect` / `.read` | `200ms` / `1s` |
| `spring.kafka.consumer.properties.session.timeout.ms` / `spring.kafka.consumer.heartbeat-interval` | `3000` / `1s` (shorter than `answer-deadline-offset`, §4) |
| `tpe.csm.mtls.*` | keystore / truststore, `prod` only |
| `tpe.bank-directory.location` | see [01 §1.4](01-shared-libraries.md#14-bank-directory) (own BIC) |

## 8. Non-functional requirements

- Command consumed → `pacs.008` transmitted ≤ 100 ms p99.
- Inbound `pacs.008` → `CreditTransferReceived` in outbox ≤ 50 ms p99.
- Answer deadline fires within 100 ms after `answer_deadline` p99 (poll interval 50 ms).
- Deployable with ≥ 2 instances; all coordination through the DB.
- Metrics: `tpe.csm.messages{direction,type}`, `tpe.csm.notsent{reason}`, `tpe.csm.answer{status,decidedBy}`,
  `tpe.csm.verdict.ignored`, `tpe.csm.answer.margin` (deadline − answered_at, histogram),
  `tpe.csm.transmit.attempts{type}`, `tpe.csm.uncorrelated`, plus `tpe.csm.stage.duration`
  ([25 §11](25-service-levels.md#11-metrics-to-add)).
- Service levels: SLO-GW-01 … -09 in [25 §5.6](25-service-levels.md#56-csm-gateway); the scheme SLA-06 rests on them.

## 9. Acceptance criteria

- **GW-AC-01** A `SendCreditTransfer` consumed before `sendBy` produces exactly one XSD-valid `pacs.008`, journalled
  before transmission. (UC-04 5)
- **GW-AC-02** A `SendCreditTransfer` consumed at or after `sendBy` produces no `pacs.008` and one
  `CreditTransferNotSent(AB03)`. (UC-04 5a, ADR-004)
- **GW-AC-03** CSM unreachable: `pacs.008` retried with the same `MsgId` until `sendBy`, never after (assert on the
  simulator's received log with a controlled clock).
- **GW-AC-04** Gateway stopped between journal commit and first transmission, restarted after `sendBy`: no
  `pacs.008` reaches the CSM and `CreditTransferNotSent` is published.
- **GW-AC-05** `RequestPaymentStatus` for a `NOT_SENT` transfer produces `CreditTransferNotSent` and no `pacs.028`. (UC-05 2a)
- **GW-AC-06** `RequestPaymentStatus` for a `SENT` transfer produces a `pacs.028`; its `pacs.002 ACSC` answer
  produces `CreditTransferSettled(source = INVESTIGATION)`. (UC-05 3–5)
- **GW-AC-07** `UNKNOWN_TX` answer after `deadline_at` for a `SENT` transfer → `CreditTransferRejected(AB05)`;
  before `deadline_at` → no event. (UC-05 4c)
- **GW-AC-08** Valid incoming `pacs.008` → one `CreditTransferReceived` with `answerDeadline = receivedAt + 5s`. (UC-06 1)
- **GW-AC-09** Schema-invalid `pacs.008` → `pacs.002 RJCT FF01`, no event. (UC-06 1a)
- **GW-AC-10** Duplicate `pacs.008` before the answer → dropped; after the answer → the same `pacs.002` re-sent. (UC-06 1b)
- **GW-AC-11** Verdict before the deadline → that answer + `AnswerSent(VERDICT)`; verdict after the deadline →
  `RJCT AB05` + `AnswerSent(DEADLINE)` only, late verdict ignored. (UC-06 6, 6b)
- **GW-AC-12** Race test: 1 000 incoming transfers with verdicts arriving uniformly in `[deadline − 50 ms,
  deadline + 50 ms]` across 2 gateway instances → exactly one `pacs.002` answer and one `AnswerSent` per `TxId`,
  and they agree.
- **GW-AC-13** A duplicate `pacs.002` with the same status publishes nothing; a contradicting one publishes the
  contradicting event and keeps the first status in the record. (UC-04 8a)
- **GW-AC-14** A `pacs.008` containing a DOCTYPE is rejected without entity resolution. (ISO-AC-02)
- **GW-AC-15** Two valid `pacs.008` with the same `TxId` and different `DbtrAgt` BICs → two `incoming_transfers`
  rows, two `CreditTransferReceived`, and verdicts for each produce their own `pacs.002`; neither is treated as a
  duplicate of the other. (ADR-010)
- **GW-AC-16** An answer whose transmission keeps failing is retried with the same `answer_msg_id` until
  `answer_deadline + answer-transmit-window`, then marked `GAVE_UP`; no attempt is made after that and the published
  `AnswerSent` stands. (§6.5)
- **GW-AC-17** Two incoming transfers with the same `TxId` and different `DbtrAgt` BICs, both without a settlement
  confirmation: their `RequestPaymentStatus(INCOMING, attempt 1)` commands produce two `status_requests` rows and
  two `pacs.028`; each `pacs.002` answer settles or rejects only its own transfer. (ADR-010)
