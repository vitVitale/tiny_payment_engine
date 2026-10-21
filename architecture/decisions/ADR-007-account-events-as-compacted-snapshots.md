# ADR-007 Account events carry full snapshots on a compacted topic

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** [ADR-002](ADR-002-rest-plus-kafka-with-outbox.md)
- **Resolves:** [Q-14](../../specs/40-open-questions.md#q-14-accounts-topic), prepares [Q-16](../../specs/40-open-questions.md#q-16-holder-data-changes)

## Context
vop-service and risk-service keep their own projection of account holders, fed by `accounts.events` (ADR-002). Both
must be able to start from zero: a new service, a new instance of a projection after a bug fix, or a disaster
recovery. Two problems follow if events only describe the change ("account X blocked"):

- With time-based retention, events older than the retention are gone, so a projection cannot be rebuilt.
- With infinite retention, the topic grows forever and a rebuild replays every block / unblock ever made.

Holder data also needs to change some day (name change, new address country), which the current event set
(`AccountOpened` / `Blocked` / `Unblocked` / `Closed`) cannot express.

## Decision
- `accounts.events` is a **log-compacted** topic keyed by `accountId` (`cleanup.policy=compact`,
  `min.compaction.lag.ms=1h`, `delete.retention.ms=7d`).
- **Every** account event carries the full current account snapshot (holder data, status, active block reasons) and a
  `revision` that increases by one with each lifecycle or holder change of that account (not with postings).
- Consumers **upsert** the snapshot when `revision` is greater than the stored one and ignore it otherwise. They do not
  need the inbox for this topic; the revision check makes handling idempotent.
- Event types stay as names of *what happened* (for audit and metrics); consumers treat them all the same.
  `AccountHolderUpdated` is added when holder changes are built ([Q-16](../../specs/40-open-questions.md#q-16-holder-data-changes)),
  with no consumer change.
- Rebuilding a projection = reset the consumer group offset to the beginning and replay.

## Options considered
| Option | Complexity | Rebuild | Assessment |
|---|---|---|---|
| A. Delta events, 7-day retention | Low | Impossible after 7 days | Projections cannot be recreated. |
| B. Delta events, infinite retention | Low | Full history replay | Topic and replay time grow forever; every consumer must apply every delta correctly in order. |
| **C. Snapshot events, compacted** | Low | Latest state per account | Small topic, simple idempotent upsert, history not needed by consumers. |
| D. Bulk sync REST endpoint on ledger-service | Medium | Request / response | Couples consumers to ledger availability; a second code path next to the events. |

## Trade-off analysis
C trades event size (≈ 0.5 KB per event instead of ≈ 0.1 KB) and loss of history on the topic for trivial rebuilds and
idempotent consumers. The history is not lost: ledger-service keeps it in `audit_log` and `account_blocks`. Personal
data on a compacted topic is a privacy concern: a closed account's snapshot stays on the topic forever unless removed.
Therefore, 13 months after an account is closed, ledger-service publishes a **tombstone** (`null` value) for its key, and
consumers delete the projection row.

## Consequences
- Projections are rebuildable at any time; consumer logic is a single upsert.
- Producer must always send the whole snapshot; ledger builds it from the account row in the same transaction.
- Every consumer must handle `null` values (tombstones) by deleting the row.
- Ordering per account still relies on the key and on the single relay ([ADR-006](ADR-006-ordered-outbox-relay.md));
  the revision check makes the rare reordering harmless anyway.

## Action items
1. [ ] Topic settings in `deploy/` and in the topic-creation job ([02 §2](../../specs/02-messaging-contracts.md#2-topics)).
2. [ ] Ledger: `revision` column, snapshot builder, tombstone job for closed accounts.
3. [ ] vop / risk: revision-guarded upsert, tombstone delete, rebuild runbook.
