# ADR-010 Identity of incoming transactions and pending credits

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** [ADR-005](ADR-005-ledger-booking-model.md) (pending credit id)
- **Resolves:** [Q-07](../../specs/40-open-questions.md#q-07-pending-credit-id-and-incoming-txid-uniqueness)

## Context
The design uses `TxId` as the identity of an incoming payment in three places: csm-gateway deduplicates `pacs.008` by
it, payment-orchestrator makes `(direction, TxId)` unique, and ADR-005 sets the pending credit id to `TxId`.

`TxId` is assigned by the first instructing agent — the Originator PSP — and ISO 20022 only requires it to be unique
for that agent. Two PSPs may both send `TxId = "20261021-000001"`. With `TxId` alone, the second payment would be
dropped as a duplicate (gateway), refused (orchestrator unique constraint), or — worst — matched to the first
payment's pending credit if the amount is equal (ledger idempotency). `TxId` is also free text (`Max35Text`, may
contain `/`, `+`, spaces), which is awkward in URL paths.

## Decision
- The **business identity** of an interbank transaction is the pair **(`debtorAgentBic`, `TxId`)**, where
  `debtorAgentBic` is the `DbtrAgt` BIC of the `pacs.008` (BIC8 + branch, as received). For outgoing payments it is
  our own BIC, so the rule is the same in both directions.
  - csm-gateway: `incoming_transfers` primary key `(debtor_agent_bic, tx_id)`; duplicate detection on that pair;
    inbound `pacs.002` correlation uses `OrgnlTxId` plus `OrgnlTxRef/DbtrAgt` (fallback: the journalled `OrgnlMsgId`).
  - payment-orchestrator: unique `(direction, debtor_agent_bic, tx_id)`.
  - Messages on `csm.*` topics carry `debtorAgentBic` next to `txId`; dedup keys include it.
- The Kafka **message key stays `TxId`**. A key only chooses the partition; two transactions that share a `TxId`
  landing on the same partition costs nothing.
- The **pending credit id is the incoming `paymentId`** (UUID v7, assigned by the orchestrator when it creates the
  incoming payment), not the `TxId`. The ledger stores `debtor_agent_bic` and `tx_id` as reference columns.

## Options considered
| Option | Complexity | Correctness | Assessment |
|---|---|---|---|
| A. `TxId` alone (status quo) | Low | Collisions between PSPs are possible | Rare but silent; unacceptable for money. |
| **B. (`debtorAgentBic`, `TxId`) everywhere; pending credit id = `paymentId`** | Low | Unique by the ISO definition | Small schema / contract change before any code exists. |
| C. `UETR` as identity | Low | Unique by definition (UUID v4) | `UETR` is mandatory in SCT Inst `pacs.008`, but the CSM correlates status messages by `TxId` / `MsgId`; we would still need B for correlation, so it adds a second key. |
| D. Hash of (`debtorAgentBic`, `TxId`) as a single id | Low | Same as B | Opaque in logs and admin tools; no benefit over a composite key. |

## Trade-off analysis
B costs one extra column in three tables and one field in the CSM messages, and removes a silent-failure risk. Changing
it now is cheap: no code exists yet. Using `paymentId` for the pending credit also gives the ledger the same URL-safe
UUID ids for holds and pending credits.

## Consequences
- Contracts: `debtorAgentBic` is required on every `csm.*` message with `direction = INCOMING`
  ([02](../../specs/02-messaging-contracts.md)).
- The incoming `paymentId` must exist before the pending credit is created — already true (it is created on
  `CreditTransferReceived`).
- Admin tools search incoming payments by `TxId` and show all matches, since a `TxId` alone may match more than one.

## Action items
1. [ ] Update contracts, gateway, orchestrator and ledger specs (done together with this ADR).
2. [ ] System test: two incoming payments from different debtor agents with the same `TxId` are both credited.
