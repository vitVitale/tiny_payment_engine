# ADR-006 Ordered outbox relay: one active publisher per service

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** [ADR-002](ADR-002-rest-plus-kafka-with-outbox.md)
- **Resolves:** [Q-03](../../specs/40-open-questions.md#q-03-outbox-relay-concurrency)

## Context
ADR-002 publishes every Kafka message through a transactional outbox and a relay, but does not say how many relay
instances publish at the same time. Every service runs at least two instances.

[ADR-004](ADR-004-csm-gateway-owns-scheme-timing.md) depends on per-`TxId` order on `csm.outbound.commands`: csm-gateway
must handle `SendCreditTransfer` before `RequestPaymentStatus` for the same payment, or it would answer "not sent" for a
message it is about to send. Kafka keeps order per partition only if the producer writes in order. If two relay
instances each lock a batch with `FOR UPDATE SKIP LOCKED`, instance B can publish row 102 before instance A has
published row 101, and order per key is lost. The same applies to `csm.inbound.events`
(`CreditTransferReceived` → `AnswerSent` → `CreditTransferSettled`).

Expected volume is small: about 4 outbox rows per payment and at most 300 payments/s at peak
([Q-13](../../specs/40-open-questions.md#q-13-target-load)), so about 1 200 rows/s for payment-orchestrator, the busiest producer.

## Decision
- Each service has **exactly one active relay**. Every instance tries `pg_try_advisory_lock(hashtext('<service>-outbox'))`
  on a dedicated connection; the holder publishes, the others retry every 2 s. If the holder dies, its session ends,
  PostgreSQL releases the lock and another instance takes over.
- The relay reads unpublished rows in `id` order, sends the whole batch, waits for all acks, then marks the batch
  published. On a send failure it stops at the first failed row and retries from the oldest unpublished row.
- Producer settings keep order inside one producer: `enable.idempotence=true`, `max.in.flight.requests.per.connection ≤ 5`.
- `BIGSERIAL` ids can commit out of order (a transaction that took id 101 commits after one that took id 102). The
  relay therefore reads only rows older than a 20 ms **visibility window** or older than every in-progress writer
  transaction (`txid_current_snapshot()` xmin check), whichever the implementation picks. Rows of the same key are
  written by successive transactions on the same aggregate, so the window only needs to cover concurrent writers of
  different keys.

## Options considered
| Option | Complexity | Throughput | Order per key | Assessment |
|---|---|---|---|---|
| A. Any instance publishes (`SKIP LOCKED` batches) | Low | Scales with instances | **Not guaranteed** | Breaks ADR-004 under load or after a pause. |
| **B. One active relay per service (advisory lock)** | Low | One producer: ~5 000–10 000 msg/s with batching | Guaranteed | Enough for 4–8× the expected peak. |
| C. N relay shards, each owning `hash(key) mod N` with its own advisory lock | Medium | Scales to N producers | Guaranteed | The upgrade path if B becomes the bottleneck. |
| D. CDC (Debezium) on the outbox table | High (new runtime component) | High | Guaranteed (WAL order) | Already named as the fallback in ADR-002 for latency; too much operations for the MVP. |

## Trade-off analysis
B costs some failover time (up to 2 s until another instance takes the lock, plus the time PostgreSQL takes to notice a
dead session) and a throughput ceiling of one producer. Failover delay matters because it eats into the 10 s send-by
budget; it is bounded and covered by the send-by guard — a command that arrives late is not sent. A loses
ordering, which is a correctness bug, not a performance trade-off. C keeps B's guarantee and removes its ceiling, so B
can be replaced by C later without changing consumers.

## Consequences
- Order per key on every topic, which ADR-004 needs.
- Relay failover adds up to ~2 s of extra latency for messages produced during the failover; monitored with
  `tpe.outbox.publish.latency` and `tpe.outbox.pending`.
- Session-level advisory locks need a dedicated connection that is not returned to the pool (HikariCP); the relay
  must detect a lost connection and stop publishing immediately.
- Revisit when `tpe.outbox.pending` grows under sustained load: switch to option C.

## Action items
1. [ ] Implement the leader lock and the visibility window in `messaging` ([01 §3.3](../../specs/01-shared-libraries.md#33-outbox-relay)).
2. [ ] Test MSG-AC-03 (per-key order with two instances) and a failover test (kill the leader under load, no reordering, no loss).
3. [ ] Alert when no instance has held the relay lock for 10 s.
