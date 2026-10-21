# ADR-002 REST for synchronous calls, Kafka with transactional outbox for asynchronous messaging

- **Status:** Accepted
- **Date:** 2026-10-15

## Context
An SCT Inst payment must be confirmed within 10 s (20 s hard deadline). Some steps need an immediate answer
(risk decision, funds reservation), others are naturally asynchronous (CSM round-trip, notifications).
Losing or duplicating a message can mean lost or duplicated money.

## Decision
- **REST/JSON** (Spring `RestClient`) for request/response calls inside the time budget:
  orchestrator → risk-service, orchestrator → ledger-service, payment-api → orchestrator / vop-service,
  risk-service → ledger-service (block / unblock accounts).
  Every call has a timeout derived from the remaining payment deadline (where there is one) and an idempotency key.
- **Kafka** for asynchronous commands and events. Topics:

  | Topic | Producer | Consumers | Key |
  |---|---|---|---|
  | `csm.outbound.commands` | payment-orchestrator | csm-gateway | `TxId` |
  | `csm.inbound.events` | csm-gateway | payment-orchestrator | `TxId` |
  | `<topic>.dlt` | consumer of `<topic>` | operations tooling | same as source |
  | `payments.events` | payment-orchestrator | notification-service | `paymentId` |
  | `accounts.events` | ledger-service | vop-service, risk-service | `accountId` |

- Producers use a **transactional outbox**: the message is written to an `outbox` table in the same DB transaction as the state change,
  and a relay publishes it to Kafka. The relay polls the outbox table and is woken early by PostgreSQL `LISTEN` / `NOTIFY` on insert;
  CDC (e.g. Debezium) is the fallback if measured latency is too high.
- Consumers use an **inbox** table to make handling idempotent. The dedup key is the business key, not the event id:
  `TxId` + event type + outcome for CSM events (csm-gateway may republish a duplicate `pacs.002` under a new event id),
  event id for the other topics.
- **Poison messages:** a message that cannot be deserialised or fails validation is retried a few times in place, then moved to
  `<topic>.dlt` with the error, so it never blocks its partition. Every DLT message raises an alert; a message on a `csm.*` DLT
  also opens an exception case for the affected payment.
- **Consumer lag** on `csm.*` topics is monitored per partition and alerts well before it eats into the payment deadlines
  (e.g. lag > 1 s).
- Event payloads carry the ids consumers need (`paymentId`, `TxId`, debtor / creditor account ids), so consumers such as
  notification-service do not need their own copy of account data.
- Messages are keyed so all messages of one payment / account land on the same partition (ordering per entity).

## Consequences
- Effectively-once processing without distributed transactions.
- Kafka is on the critical path four times per payment (two relay hops, two consumer hops). The relay adds latency
  (target < 50 ms); end-to-end latency must be measured against the 10 s budget and drives the `sendBy` and answer-deadline
  values of [ADR-004](ADR-004-csm-gateway-owns-scheme-timing.md).
- A dead-letter topic per consumed topic and the related alerting are part of the `messaging` library.
- Event schemas live in the `contracts` library and must evolve backward-compatibly.
