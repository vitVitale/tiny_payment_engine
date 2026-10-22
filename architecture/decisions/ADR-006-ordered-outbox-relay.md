# ADR-006 Ordered outbox relay: one active publisher per service

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amended:** 2026-10-22 — bounded failover (see [Amendment](#amendment-2026-10-22-bounded-failover))
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
  on a dedicated connection; the holder publishes, the others retry every 2 s (1 s since the amendment). If the holder
  dies, its session ends, PostgreSQL releases the lock and another instance takes over — within a bounded time only with
  the heartbeat of the amendment.
- The relay reads unpublished rows in `id` order, sends the whole batch, waits for all acks, then marks the batch
  published. On a send failure it stops at the first failed row and retries from the oldest unpublished row.
- Producer settings keep order inside one producer: `enable.idempotence=true`, `max.in.flight.requests.per.connection ≤ 5`.
- `BIGSERIAL` ids can commit out of order (a transaction that took id 101 commits after one that took id 102). The
  relay therefore reads only rows older than a 20 ms **visibility window** or older than every in-progress writer
  transaction (`txid_current_snapshot()` xmin check), whichever the implementation picks. Rows of the same key are
  written by successive transactions on the same aggregate, so the window only needs to cover concurrent writers of
  different keys.

## Amendment 2026-10-22: bounded failover

**Status:** Accepted. Raised by [25 §13 R-1](../../specs/25-service-levels.md#13-risks-and-what-to-revisit).

### Context
"If the holder dies, its session ends" holds for a killed **process**: the socket closes and PostgreSQL ends the
session at once. It does not hold when the holder's **node** disappears, its network is partitioned, or its JVM is
frozen (VM pause, long GC): PostgreSQL keeps the idle session, and with it the lock, until TCP keepalive gives up —
two hours with default kernel settings. A stuck publish loop in a live JVM holds the lock forever. Meanwhile nothing
is published: every payment accepted during the gap misses its `sendBy` (`AB03`), and incoming verdicts miss the answer
deadline. The send-by guard keeps this correct, but the relay gap would consume most of the 8.8 s margin between our
p99 and `sendBy` ([25 §3.2](../../specs/25-service-levels.md#32-outgoing-latency-budget-scheme-target-10-s-sendby-10-s-deadline-20-s)).
Target: another relay publishes ≤ 5 s after the leader stops (SLO-MSG-02).

### Decision
- **Server-side lease.** The relay sets `SET idle_session_timeout = '3s'` on its lock connection only (PostgreSQL ≥ 14),
  and the leader runs `SELECT 1` on that connection every 1 s (heartbeat). If the leader stops heartbeating for any
  reason — dead node, partition, frozen JVM — PostgreSQL terminates the session after 3 s and the lock is released.
  The timer runs in the server, so it does not depend on reaching the client.
- **Faster takeover.** Followers retry `pg_try_advisory_lock` every 1 s (was 2 s). Failover ≤ 3 s + 1 s + one heartbeat
  interval ≈ 5 s.
- **Progress watchdog.** The leader heartbeats only while its publish loop completes iterations: a loop iteration that
  ends in a failed send and a back-off still counts (otherwise a Kafka outage would make leadership flap between
  instances, which helps nobody). A loop that has not completed an iteration for 2 s stops the heartbeat, closes the
  lock connection and marks the instance not live (`/actuator/health/liveness`), so a deadlocked publisher hands over too.
- **Old leader stops.** Before reading each batch the relay checks that the last heartbeat succeeded. A failed
  heartbeat (session terminated) makes it stop publishing, discard the connection and rejoin as a follower with a new one.
- **No skipping.** Every relay, leader or not, reads `WHERE published_at IS NULL ORDER BY id` from the oldest
  unpublished row, with no `SKIP LOCKED` and no offset of its own. This rule is what makes the overlap below safe.

### Why an overlap is safe without a fencing token
A frozen old leader can wake up after the new leader took over and finish the batch it had already read. For a key
with messages `m1` before `m2` (same key ⇒ successive transactions ⇒ `id(m1) < id(m2)`, both visible together):

- each relay sends a contiguous, `id`-ordered run of unpublished rows through one idempotent producer, so on each
  producer `m1` is sent before `m2`, or `m1` was already acknowledged earlier and marked published;
- therefore, whichever producer delivers the **first** copy of `m2`, a copy of `m1` is already in the partition;
- consumers deduplicate by their inbox key, so the extra copies have no effect (MSG-AC-06), and the first copies
  arrive in order.

The overlap costs duplicates — at most the batch (≤ 200 rows) the old leader had in flight — never reordering or
loss. Option A of the table below breaks order precisely because `SKIP LOCKED` lets one relay send `m2` while
another holds `m1`. A fencing token (lock epoch checked on every Kafka write) would remove the duplicates, but Kafka
cannot check it, so it would need a transactional producer per epoch: more machinery for no correctness gain.

### Options considered (amendment)
| Option | Failover bound | Assessment |
|---|---|---|
| Kernel TCP keepalive tuning on the DB server | ~5–10 s for dead nodes only | Does not cover a frozen JVM or a stuck loop; host-level setting outside the service's control. |
| `tcp_user_timeout` on the client connection | client side only | Detects the loss at the client, not at the server, so the lock stays. |
| **`idle_session_timeout` + heartbeat + progress watchdog** | ≤ 5 s for every case | Per-session setting, no infrastructure change; covers node loss, partition, freeze and stuck loop. |
| Lock in a table with a lease column (`leader_until`) | lease length | Same guarantees, more code; the advisory lock keeps release-on-disconnect for free. |

### Consequences of the amendment
- A JVM pause longer than 3 s costs the instance its leadership. That is intended: a 3 s pause is an incident in itself.
- Failover duplicates are visible in `tpe.inbox.duplicates`; a rise during a failover is expected, not an alert.
- `tpe.relay.active` can briefly be 1 on two instances (the old leader has not noticed yet). Alert only if that lasts
  longer than 5 s ([25 §9.2](../../specs/25-service-levels.md#92-cause-alerts-page-regardless-of-budget)).
- Tested by CH-13 ([20 §5](../../specs/20-testing-strategy.md#5-failure-injection-scenarios)) and MSG-AC-08.

## Options considered
| Option | Complexity | Throughput | Order per key | Assessment |
|---|---|---|---|---|
| A. Any instance publishes (`SKIP LOCKED` batches) | Low | Scales with instances | **Not guaranteed** | Breaks ADR-004 under load or after a pause. |
| **B. One active relay per service (advisory lock)** | Low | One producer: ~5 000–10 000 msg/s with batching | Guaranteed | Enough for 4–8× the expected peak. |
| C. N relay shards, each owning `hash(key) mod N` with its own advisory lock | Medium | Scales to N producers | Guaranteed | The upgrade path if B becomes the bottleneck. |
| D. CDC (Debezium) on the outbox table | High (new runtime component) | High | Guaranteed (WAL order) | Already named as the fallback in ADR-002 for latency; too much operations for the MVP. |

## Trade-off analysis
B costs some failover time (up to 2 s until another instance takes the lock, plus the time PostgreSQL takes to notice a
dead session — unbounded for a lost node until the amendment, ≤ 5 s with it) and a throughput ceiling of one producer. Failover delay matters because it eats into the 10 s send-by
budget; it is bounded and covered by the send-by guard — a command that arrives late is not sent. A loses
ordering, which is a correctness bug, not a performance trade-off. C keeps B's guarantee and removes its ceiling, so B
can be replaced by C later without changing consumers.

## Consequences
- Order per key on every topic, which ADR-004 needs.
- Relay failover adds up to ~5 s of extra latency for messages produced during the failover (amendment); monitored with
  `tpe.outbox.publish.latency` and `tpe.outbox.pending`.
- Session-level advisory locks need a dedicated connection that is not returned to the pool (HikariCP); the relay
  must detect a lost connection and stop publishing immediately.
- Revisit when `tpe.outbox.pending` grows under sustained load: switch to option C.

## Action items
1. [ ] Implement the leader lock and the visibility window in `messaging` ([01 §3.3](../../specs/01-shared-libraries.md#33-outbox-relay)).
2. [ ] Test MSG-AC-03 (per-key order with two instances) and a failover test (kill the leader under load, no reordering, no loss).
3. [ ] Alert when no instance has held the relay lock for 5 s (was 10 s; SLO-MSG-02).
4. [ ] Heartbeat, `idle_session_timeout`, progress watchdog and the no-skipping rule in `messaging`; MSG-AC-08 (amendment).
5. [ ] CH-13 nightly: freeze and partition the leader (amendment).
