# ADR-008 Resumable saga steps: persisted pending action, remote calls outside transactions

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** [ADR-003](ADR-003-orchestrated-saga.md)
- **Resolves:** [Q-04](../../specs/40-open-questions.md#q-04-saga-resume-mechanism), [Q-15](../../specs/40-open-questions.md#q-15-on-us-payments-and-the-20-s-deadline)

## Context
ADR-003 says saga state is persisted after every step and the saga resumes from the last state after a restart. It
does not say *what* resumes it or how a step that was half done (remote call made, result not yet stored) is
finished. Three constraints shape the answer:

- Steps call other services over REST (risk, ledger). Holding a DB transaction or row lock during a remote call
  ties up connections and makes the 1 s internal budget depend on the slowest dependency.
- CSM events, timers and step results can arrive for the same payment at the same time, on different instances.
- On-us payments have no CSM, but ADR-003 also applies the 20 s deadline to them. If the deadline rejects an on-us
  payment while the `settle-on-us` call is in flight, the hold release fails because the hold has just been settled:
  the payer is debited and the payment says `REJECTED`.

## Decision
- The payment row stores the next remote step: `pending_action` (e.g. `CREATE_HOLD`, `SETTLE_HOLD`), `next_action_at`
  and `action_attempts`. A step follows **record → act → transition**:
  1. the transaction that enters a state also sets the next `pending_action`;
  2. a runner claims the action with a conditional update (`next_action_at = now + lease`) and performs the remote
     call **outside** any transaction;
  3. a second transaction applies the result — only if the payment is still in the same state with the same
     `pending_action` (optimistic lock) — and sets the next action.
- Fast path: after commit the runner starts the action immediately. Recovery path: a sweeper picks actions whose
  lease expired or whose retry time has come. No separate "resume after restart" code exists — recovery is the
  normal path.
- Every remote call is idempotent (ledger `Idempotency-Key`, risk `assessmentId`), so running an action twice is harmless.
- When an event or timer overtakes a step and rejects the payment, it sets the compensating action itself
  (`RELEASE_HOLD` / `CANCEL_CREDIT`). The overtaken step's result is discarded. The ledger tombstones of
  [ADR-009](ADR-009-ledger-tombstones.md) make the compensation safe even if the overtaken call is still on its way.
- **On-us exception to the 20 s deadline:** once funds are reserved, the deadline does not reject an on-us payment. Its
  actions (`ASSESS_PAYEE_RISK`, `SETTLE_ON_US`) retry until they get a definitive answer. The 20 s deadline exists for
  the CSM round trip; on-us has none, and a definitive local answer is always reached quickly when the ledger is up.

## Options considered
| Option | Complexity | Crash safety | Assessment |
|---|---|---|---|
| A. In-memory flow, restart recovery job scanning non-final payments | Low | Weak: the job must re-derive which call was in flight from the state | Two code paths; the recovery path is rarely exercised and therefore buggy. |
| B. Remote calls inside the DB transaction, row lock held | Low | Good | Locks held for up to the call timeout; a slow ledger exhausts the connection pool. |
| **C. Persisted pending action, lease, sweeper** | Medium | Good, and the recovery path is exercised constantly | Fits the existing timer pattern (`FOR UPDATE SKIP LOCKED`). |
| D. Workflow engine (Temporal, Camunda) | High | Very good | Rejected by ADR-003 for now; C keeps the option open because steps are already explicit and idempotent. |

## Trade-off analysis
C needs a little more schema (three columns, one partial index) and discipline (every result is applied
conditionally), but it makes crash recovery the same code as normal processing and keeps transactions short. The
on-us exception gives up a hard time limit for on-us payments in exchange for never ending with a debited payer and a
rejected payment; an on-us payment stuck because the ledger is down shows `PDNG` and opens a `LEDGER_ACTION_STUCK` case
after 10 minutes.

## Consequences
- A payment is never "lost" between steps; the sweeper finds it within one lease (5 s by default).
- Duplicate remote calls happen after crashes or lease expiry; idempotency on the receiving side is mandatory.
- Kill tests per action (ORC-AC-13) become the main proof of ADR-003's resume guarantee.
- On-us payments may finalise after 20 s if the ledger is slow; the customer API keeps showing `PDNG` meanwhile.

## Action items
1. [ ] Implement runner, claim and sweeper ([11 §6.2–6.3](../../specs/11-payment-orchestrator.md#62-pending-actions)).
2. [ ] Parameterised kill test over every `pending_action`.
3. [ ] Alert on `tpe.worker.lag{worker="saga-sweeper"}` > 5 s.
