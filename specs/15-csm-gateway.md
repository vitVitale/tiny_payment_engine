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
| `application.outgoing` | Outgoing payment handler (send-by guard, status requests) |
| `application.incoming` | Incoming payment handler |
| `application.answer` | Answer coordinator |
| `application.correlation` | Correlation service |
| `application.transmit` | Transmitter (§6.5) |
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
messages. `400` only if the envelope cannot be parsed at all (no `TxId` can be extracted). In `prod` the
connection uses mTLS; `local` / `test` use plain HTTP.

### 3.2 Admin API (role `BACKOFFICE_OPERATOR`, read-only)

- `GET /v1/journal?txId=…` — all journalled messages for a transaction (XML, direction, timestamps, transport status).
- `GET /v1/transactions/{txId}` — outgoing or incoming transaction record (§5).

## 4. Messaging

| Direction | Topic | Messages | Dedup |
|---|---|---|---|
| consume | `csm.outbound.commands` | `SendCreditTransfer`, `RequestPaymentStatus`, `AcceptCreditTransfer`, `RejectCreditTransfer` | `<type>:<txId>[:<attempt>]` |
| produce | `csm.inbound.events` | `CreditTransferReceived`, `AnswerSent`, `CreditTransferSettled`, `CreditTransferRejected`, `CreditTransferNotSent` | — |

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
  answer_msg_id    VARCHAR(35),
  transmit_status  VARCHAR(10) CHECK (transmit_status IN ('PENDING','DELIVERED','GAVE_UP')),
  transmit_attempts INT NOT NULL DEFAULT 0,
  next_transmit_at TIMESTAMPTZ,
  final_status     VARCHAR(10) CHECK (final_status IN ('SETTLED','REJECTED')),
  final_reason     VARCHAR(35),
  final_at         TIMESTAMPTZ,
  version          BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (debtor_agent_bic, tx_id)
);
CREATE INDEX incoming_unanswered ON incoming_transfers (answer_deadline) WHERE answer_status IS NULL;

CREATE TABLE status_requests (
  tx_id VARCHAR(35) NOT NULL, direction VARCHAR(8) NOT NULL, attempt INT NOT NULL,
  msg_id VARCHAR(35) NOT NULL UNIQUE, created_at TIMESTAMPTZ NOT NULL,
  transmit_status VARCHAR(10) NOT NULL, transmit_attempts INT NOT NULL DEFAULT 0, next_transmit_at TIMESTAMPTZ,
  PRIMARY KEY (tx_id, direction, attempt)
);

CREATE TABLE message_journal (
  id          BIGSERIAL PRIMARY KEY,
  direction   VARCHAR(3) NOT NULL CHECK (direction IN ('IN','OUT')),
  message_type VARCHAR(20) NOT NULL,          -- pacs.008.001.08, …
  msg_id      VARCHAR(35),
  tx_id       VARCHAR(35),
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

1. Validate envelope + document against XSD and check `InstdAgt` / `CdtrAgt` BIC = our BIC, `IntrBkSttlmCcy = EUR`,
   `NbOfTxs = 1`. Journal `IN` with the result.
2. Invalid but `TxId` readable → create `incoming_transfers` row with `answer_status = RJCT`, `answer_reason = FF01`,
   `answer_decided_by = VALIDATION`, transmit `PENDING`. No event — no payment is created (UC-06 1a).
3. Duplicate (`DbtrAgt` BIC, `TxId`) (row exists): if an answer was decided → reset `transmit_status = PENDING` to re-send the same
   answer `pacs.002` (same `answer_msg_id`); else drop. Journal note either way; no event (UC-06 1b).
4. New: insert row with `received_at = now`, `answer_deadline = now + answer-deadline-offset`; outbox
   `CreditTransferReceived`. Commit → `202`.

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
  0 rows → late or second verdict: journal note `verdict ignored`, metric `tpe.csm.verdict.ignored`, no event.
- **Deadline** (`PollingWorker`, 50 ms, `WHERE answer_status IS NULL AND answer_deadline <= :now FOR UPDATE SKIP
  LOCKED`): set `RJCT`, `AB05`, `DEADLINE`, build / journal `pacs.002`, outbox `AnswerSent(RJCT, AB05, DEADLINE)`
  (UC-06 6b).

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
| | `RJCT` + `UNKNOWN_TX` (answer to `pacs.028`) | if `send_status = SENT` and `now ≥ deadline_at` → `REJECTED AB05`, outbox `CreditTransferRejected(OUTGOING, AB05, INVESTIGATION)` (UC-05 4c); otherwise journal only |
| `incoming_transfers` | `ACSC` | set `SETTLED`, outbox `CreditTransferSettled(INCOMING)` (UC-06 8) |
| | `RJCT` | set `REJECTED`, outbox `CreditTransferRejected(INCOMING, reason)` (UC-06 8a) |
| none | any | journal with note `uncorrelated`, alert |

- Same final status again → journal only (duplicate).
- A *different* final status after one was recorded → journal, keep the first in the row, and publish the
  contradicting event anyway; the orchestrator opens a case (UC-04 8a). The `source` field is `STATUS_REPORT` or
  `INVESTIGATION` (if the `pacs.002` answers a `pacs.028` we sent, matched via `status_requests.msg_id`).
- `ACCP` on an outgoing transfer is informational (beneficiary accepted, settlement pending) — journal only.

### 6.5 Transmitter

- One lease-based worker for `outgoing_transfers`, `incoming_transfers` (answers) and `status_requests` rows with
  `transmit_status = PENDING AND next_transmit_at <= :now`. Claim = conditional update of `next_transmit_at = now +
  lease` and `transmit_attempts + 1`; the HTTP call happens after the claim commits (no lock held during I/O);
  an `afterCommit` fast path transmits immediately.
- `2xx` → `DELIVERED`. Error / timeout → `next_transmit_at = now + 200 ms × 2^(attempts−1)` capped at 1 s.
- Give-up windows: `pacs.008` at `sendBy` (§6.1); answers at `answered_at + answer-transmit-window`; status requests
  after 5 attempts (the orchestrator's next retry sends a new one).
- Timeouts: connect 200 ms, read 1 s.

### 6.6 `RequestPaymentStatus`

Listener transaction; per direction:

| Outgoing record | Effect |
|---|---|
| none (the `SendCreditTransfer` never arrived, e.g. it went to the DLT) | outbox `CreditTransferNotSent(AB03)` — nothing was sent |
| `NOT_SENT` | outbox `CreditTransferNotSent` again (orchestrator dedups) (UC-05 2a) |
| final status known | re-publish the final event (orchestrator dedups) |
| `SENT`, no final status | insert `status_requests` row, build `pacs.028` for `OrgnlTxId`, journal, transmit (UC-05 3) |

Incoming: final status known → re-publish; else `pacs.028` as above; no record → journal + alert.

## 7. Configuration

| Property | Default |
|---|---|
| `tpe.csm.base-url` | `http://csm-simulator:8090` |
| `tpe.csm.answer-deadline-offset` | `5s` |
| `tpe.csm.answer-transmit-window` | `5s` |
| `tpe.csm.transmit.lease` | `2s` |
| `tpe.csm.timeouts.connect` / `.read` | `200ms` / `1s` |
| `tpe.csm.mtls.*` | keystore / truststore, `prod` only |
| `tpe.bank-directory.location` | see [01 §1.4](01-shared-libraries.md#14-bank-directory) (own BIC) |

## 8. Non-functional requirements

- Command consumed → `pacs.008` transmitted ≤ 100 ms p99.
- Inbound `pacs.008` → `CreditTransferReceived` in outbox ≤ 50 ms p99.
- Answer deadline fires within 100 ms after `answer_deadline` p99 (poll interval 50 ms).
- Deployable with ≥ 2 instances; all coordination through the DB.
- Metrics: `tpe.csm.messages{direction,type}`, `tpe.csm.notsent{reason}`, `tpe.csm.answer{status,decidedBy}`,
  `tpe.csm.verdict.ignored`, `tpe.csm.answer.margin` (deadline − answered_at, histogram),
  `tpe.csm.transmit.attempts{type}`, `tpe.csm.uncorrelated`.

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
