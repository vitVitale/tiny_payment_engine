# 02 Messaging contracts

All asynchronous communication. Topics and ownership come from
[ADR-002](../architecture/decisions/ADR-002-rest-plus-kafka-with-outbox.md); the CSM-boundary messages from
[ADR-004](../architecture/decisions/ADR-004-csm-gateway-owns-scheme-timing.md). Records live in `contracts`
([01 §4](01-shared-libraries.md#4-contracts)), headers in [01 §3.4](01-shared-libraries.md#34-headers).

## 1. Conventions

- Value = JSON object of the record; message type and version are in headers, not in the body.
- `amountMinor` + `currency` for every amount; instants as ISO 8601 UTC with milliseconds.
- Party objects:

  ```json
  { "name": "Anna Schmidt", "iban": "DE89370400440532013000", "accountId": "0192f0e4-…" }
  ```

  `accountId` is the ledger account id; present only when the party is our customer and the producer knows it.
- Every message about a payment carries `txId` and `debtorAgentBic` (together the transaction identity,
  [ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md)); every orchestrator message also carries
  `paymentId`. The Kafka key is `txId` alone — it only selects the partition.
- `direction`: `OUTGOING` | `INCOMING` (from our bank's point of view).

## 2. Topics

| Topic | Key | Partitions (prod / local) | Retention | Cleanup | Producer → consumers |
|---|---|---|---|---|---|
| `csm.outbound.commands` | `txId` | 12 / 3 | 7 d | delete | payment-orchestrator → csm-gateway |
| `csm.inbound.events` | `txId` | 12 / 3 | 7 d | delete | csm-gateway → payment-orchestrator |
| `payments.events` | `paymentId` | 12 / 3 | 7 d | delete | payment-orchestrator → notification-service |
| `accounts.events` | `accountId` | 6 / 3 | ∞ | **compact** | ledger-service → vop-service, risk-service |
| `<topic>.dlt` | as source | 1 | 30 d | delete | `messaging` lib → operations tooling |

Replication factor 3 and `min.insync.replicas=2` in production, 1 locally. Consumer groups: `<service>` (e.g.
`csm-gateway`). Partition count changes are forbidden once in use (they break per-key ordering).

`accounts.events` is compacted so a new projection can be rebuilt from the topic. Therefore **every account event
carries the full current account snapshot** (§5), and consumers upsert by `accountId` if `revision` is newer than
the stored one.

## 3. `csm.outbound.commands` (orchestrator → gateway)

Dedup key at the gateway: `<type>:<debtorAgentBic>:<txId>` (each command type is sent at most once per payment,
except `RequestPaymentStatus`, deduplicated as `RequestPaymentStatus:<debtorAgentBic>:<txId>:<attempt>`).

### 3.1 `SendCreditTransfer` v1

```json
{
  "paymentId": "0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10",
  "txId": "TPE01JAB3C4D5E6F7G8H9J0K1M2N",
  "uetr": "8a562c67-ca16-48ba-b074-65581be6f001",
  "endToEndId": "INV-2026-0042",
  "acceptedAt": "2026-10-21T09:15:30.000Z",
  "sendBy":     "2026-10-21T09:15:40.000Z",
  "deadlineAt": "2026-10-21T09:15:50.000Z",
  "amountMinor": 12345,
  "currency": "EUR",
  "debtor":   { "name": "Anna Schmidt", "iban": "DE…", "accountId": "…" },
  "debtorAgentBic": "TPEBDEFFXXX",
  "creditor": { "name": "Max Mustermann", "iban": "DE…" },
  "creditorAgentBic": "PEERDEFFXXX",
  "remittanceInformation": "Invoice 42"
}
```

Invariant (validated by `ContractValidator`): `acceptedAt < sendBy < deadlineAt`.

### 3.2 `RequestPaymentStatus` v1

```json
{ "paymentId": "…", "txId": "…", "debtorAgentBic": "TPEBDEFFXXX", "direction": "OUTGOING", "attempt": 1, "requestedAt": "…" }
```

Same key (`txId`) as `SendCreditTransfer`, so the gateway always processes the send first (ADR-004).

### 3.3 `AcceptCreditTransfer` v1 / `RejectCreditTransfer` v1

```json
{ "paymentId": "…", "txId": "…", "debtorAgentBic": "PEERDEFFXXX", "decidedAt": "…" }
{ "paymentId": "…", "txId": "…", "debtorAgentBic": "PEERDEFFXXX", "decidedAt": "…", "reasonCode": "AC06" }
```

Verdicts for an incoming payment. The gateway honours the first verdict only if it arrives before the answer deadline.

## 4. `csm.inbound.events` (gateway → orchestrator)

Dedup key at the orchestrator: `<debtorAgentBic>:<txId>:<type>:<outcome>` where `outcome` is the status / reason
(`ACCP`, `RJCT-AB05`, `SETTLED`, …), so a re-published duplicate `pacs.002` under a new `eventId` is still dropped (ADR-002).

| Type | Direction | Payload (besides `txId`, `debtorAgentBic`, `direction`) | Dedup outcome |
|---|---|---|---|
| `CreditTransferReceived` v1 | INCOMING | `msgId`, `uetr`, `endToEndId`, `receivedAt`, `answerDeadline`, `acceptanceDateTime`, `interbankSettlementDate`, `amountMinor`, `currency`, `debtor`, `debtorAgentBic`, `creditor`, `creditorAgentBic`, `remittanceInformation` | `RECEIVED` |
| `AnswerSent` v1 | INCOMING | `status` (`ACCP`\|`RJCT`), `reasonCode` (RJCT only), `decidedBy` (`VERDICT`\|`DEADLINE`), `sentAt` | `<status>` |
| `CreditTransferSettled` v1 | both | `paymentId` (outgoing only), `settledAt`, `source` (`STATUS_REPORT`\|`INVESTIGATION`) | `SETTLED` |
| `CreditTransferRejected` v1 | both | `paymentId` (outgoing only), `reasonCode`, `rejectedAt`, `source` | `REJECTED-<reason>` |
| `CreditTransferNotSent` v1 | OUTGOING | `paymentId`, `reasonCode` (`AB03`), `sendBy`, `decidedAt` | `NOT_SENT` |

Notes:

- `CreditTransferReceived` is published only for schema-valid, non-duplicate `pacs.008` (UC-06 1a / 1b are handled
  inside the gateway; for 1a the gateway publishes nothing — there is no payment).
- Per `txId` the gateway publishes in causal order: `CreditTransferReceived` → `AnswerSent` → `CreditTransferSettled`
  / `CreditTransferRejected`. The single-relay rule ([01 §3.3](01-shared-libraries.md#33-outbox-relay)) keeps that order
  on the topic. Consumers must still tolerate gaps (e.g. `Settled` while the payment is still `ACCEPT_REQUESTED`).

## 5. `accounts.events` (ledger → vop, risk)

All four types share the payload shape:

```json
{
  "accountId": "0192f0e4-…",
  "revision": 7,
  "occurredAt": "…",
  "account": {
    "iban": "DE…",
    "customerId": "CUST-000123",
    "status": "BLOCKED",
    "activeBlockReasons": ["SANCTIONS_PROVISIONAL"],
    "kind": "CUSTOMER",
    "holder": {
      "type": "PERSON",
      "name": "Anna Schmidt",
      "dateOfBirth": "1985-04-12",
      "countryOfResidence": "DE",
      "organisationId": null
    },
    "openedAt": "…",
    "closedAt": null
  }
}
```

| Type | Emitted when | Extra fields |
|---|---|---|
| `AccountOpened` v1 | account created | — |
| `AccountBlocked` v1 | a block is added | `blockReason` |
| `AccountUnblocked` v1 | a block is lifted (status may still be `BLOCKED` if other blocks remain) | `liftedReason` |
| `AccountClosed` v1 | account closed | — |

`customerId` is required for every published account (technical accounts are not published); an event without it
fails `ContractValidator` and goes to the DLT, because risk-service cannot screen an account it cannot group by customer.
`revision` increases by one with every lifecycle change of the account (not with postings). Consumers ignore an
event whose `revision` ≤ the stored revision. Technical accounts (`kind = TECHNICAL`) are not published.
13 months after an account is closed, ledger-service publishes a tombstone (`null` value) for its key; consumers
delete the projection row ([ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md)).

## 6. `payments.events` (orchestrator → notification)

Emitted once per payment, when it reaches a final state **and** its ledger effect is complete
(settled entry posted, or compensation done). Dedup key at consumers: `eventId`.

### 6.1 `PaymentSettled` v1

```json
{
  "paymentId": "…",
  "direction": "OUTGOING",
  "onUs": false,
  "txId": "…",
  "uetr": "…",
  "endToEndId": "INV-2026-0042",
  "amountMinor": 12345,
  "currency": "EUR",
  "debtor":   { "name": "…", "iban": "…", "accountId": "…" },
  "creditor": { "name": "…", "iban": "…", "accountId": null },
  "remittanceInformation": "Invoice 42",
  "acceptedAt": "…",
  "settledAt": "…",
  "bookingEntryId": "…"
}
```

`creditor.accountId` is set for incoming and on-us payments. `bookingEntryId` is the ledger journal entry id
(used as the `camt.054` entry reference).

### 6.2 `PaymentRejected` v1

Same fields as `PaymentSettled` without `settledAt` / `bookingEntryId`, plus `rejectedAt` and `reasonCode`.
Incoming payments are rejected only after an answer was sent, so `PaymentRejected` with `direction = INCOMING`
exists; notification-service does not notify on it ([16 §6](16-notification-service.md#6-behaviour)).

## 7. Message matrix

| Message | Producer table / trigger | Consumer action |
|---|---|---|
| `SendCreditTransfer` | orchestrator: transition `FUNDS_RESERVED → SENT_TO_CSM` | gateway: send-by guard, `pacs.008` |
| `RequestPaymentStatus` | orchestrator: deadline / settlement-overdue / investigation retry timer | gateway: `pacs.028` or `CreditTransferNotSent` |
| `AcceptCreditTransfer` | orchestrator: pending credit created → `ACCEPT_REQUESTED` | gateway: answer coordinator |
| `RejectCreditTransfer` | orchestrator: risk deny / pending credit refused / technical failure | gateway: answer coordinator |
| `CreditTransferReceived` | gateway: valid new `pacs.008` | orchestrator: create incoming payment |
| `AnswerSent` | gateway: answer committed | orchestrator: `ACCEPTED` or `REJECTED` + cancel pending credit |
| `CreditTransferSettled` | gateway: `pacs.002 ACSC` (status report or investigation answer) | orchestrator: settle hold / confirm pending credit |
| `CreditTransferRejected` | gateway: `pacs.002 RJCT`, or "unknown" after the deadline | orchestrator: release hold / cancel pending credit |
| `CreditTransferNotSent` | gateway: send-by passed, or status request for a never-sent message | orchestrator: release hold, `REJECTED AB03` |
| `PaymentSettled` / `PaymentRejected` | orchestrator: final state + ledger effect done | notification: webhooks / `camt.054` |
| `Account*` | ledger: account lifecycle change | vop / risk: upsert holder projection |
