# ADR-009 Ledger tombstones: compensation may arrive before the operation it compensates

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** [ADR-005](ADR-005-ledger-booking-model.md)
- **Resolves:** [Q-08](../../specs/40-open-questions.md#q-08-hold-and-pending-credit-tombstones)

## Context
payment-orchestrator sometimes has to compensate an operation whose outcome it does not know:

- A `create hold` call timed out and the send-by time is close, so the payment is rejected; or the deadline timer
  rejects the payment while the call is still in flight.
- A `create pending credit` call is in flight when csm-gateway reports `AnswerSent(RJCT AB05)` — the answer deadline
  passed first.

If the compensation (`release` / `cancel`) reaches the ledger **before** the slow create, a naive ledger answers "not
found", and the create then succeeds: a hold blocks the payer's money with no payment to settle or release it, or a
pending credit stays forever.

## Decision
- `release hold` and `cancel pending credit` for an **unknown id** insert a **tombstone**: a row with that id, final
  status `RELEASED` / `CANCELLED`, no account, no amount, `tombstone = true`.
- A later `create` with that id fails with `409 HOLD_RELEASED` / `PENDING_CREDIT_CANCELLED` and changes nothing.
- Both operations are race-free because the id is the primary key: whichever insert commits first wins, the other
  hits the unique constraint and re-reads.
- Compensations are therefore always safe to issue "just in case", and the orchestrator does so whenever a create
  call *may* have been made (rule in [11 §6.2](../../specs/11-payment-orchestrator.md#62-pending-actions)).
- Tombstones are kept as long as idempotency keys (30 days); a create retried after that is impossible because the
  orchestrator gives up long before.

## Options considered
| Option | Complexity | Correctness | Assessment |
|---|---|---|---|
| A. Release of unknown id → `404`, orchestrator retries until the hold appears | Low | **Wrong**: if the create never arrives, the retries never end; if it arrives later, timing decides | Rejected. |
| B. Orchestrator never compensates an unknown outcome; it first queries until it knows | Medium | Correct but slow | Adds a query loop on the critical path near the deadline. |
| **C. Tombstone on compensation of an unknown id** | Low | Correct by construction | Standard pattern for "cancel before create" races. |
| D. Holds expire automatically after N minutes | Low | Leaves money blocked for N minutes; still needs C for pending credits | Useful later as a safety net, not as the mechanism. |

## Trade-off analysis
C adds one boolean column and two rules to the ledger and removes a whole class of race conditions from the
orchestrator. The cost is that a create with a reused id can be refused for 30 days — ids are UUIDs and never reused,
so this only affects buggy callers.

## Consequences
- The orchestrator can always compensate without first knowing what happened.
- A `409 HOLD_SETTLED` on release (or `PENDING_CREDIT_CONFIRMED` on cancel) is the only "impossible" answer left; it
  means two contradicting decisions were made and opens a `CONTRADICTING_CSM_OUTCOME` case.
- Tombstones must not count as active memos in any balance or account-closing check.

## Action items
1. [ ] Implement tombstones in ledger-service ([12 §3.1](../../specs/12-ledger-service.md#31-posting-api-internal-caller-payment-orchestrator)); tests LED-AC-04 and the pending-credit equivalent.
2. [ ] Orchestrator race tests ORC-AC-16 and E2E-I-06.
