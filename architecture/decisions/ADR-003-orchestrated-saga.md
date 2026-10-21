# ADR-003 Orchestrated saga in payment-orchestrator

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amended by:** [ADR-008](ADR-008-resumable-saga-steps.md)

## Context
A payment spans several services (risk, ledger, CSM gateway, notifications) without a shared transaction. Failure at any step must
leave money in a consistent state, and scheme deadlines (20 s time-out, investigation with `pacs.028`) must be enforced centrally.

## Decision
- payment-orchestrator owns an explicit **payment state machine** and runs two sagas: outgoing ([UC-04](../use-cases/UC-04-execute-outgoing-payment.md))
  and incoming ([UC-06](../use-cases/UC-06-receive-incoming-payment.md)).
- Outgoing states: `RECEIVED → RISK_CHECKED → FUNDS_RESERVED → SENT_TO_CSM → SETTLED`, with `INVESTIGATING` and `REJECTED`.
  On-us payments go `FUNDS_RESERVED → SETTLED` directly (no CSM).
- Incoming states: `RECEIVED → ACCEPT_REQUESTED → ACCEPTED → SETTLED`, with `INVESTIGATING` (no settlement confirmation) and `REJECTED`.
  `ACCEPTED` and the `REJECTED` that follows our answer are entered only on csm-gateway's `AnswerSent` event
  ([ADR-004](ADR-004-csm-gateway-owns-scheme-timing.md)).
- Compensations are explicit: release hold (outgoing), cancel pending credit (incoming).
- `SubmitPayment` is idempotent by a `commandId` derived by payment-api from the API client and the `Idempotency-Key`
  (unique constraint in the payments DB), so a retry after a lost response returns the existing payment.
- Deadlines are persistent timers in the payments DB, picked up with row locks (`SELECT … FOR UPDATE SKIP LOCKED`), so any instance can fire them.
- Saga state is persisted after every step; on restart the saga resumes from the last state. All downstream operations are idempotent.
- No external workflow engine (e.g. Temporal) for now.

## Consequences
- Payment flow logic is in one place and easy to reason about and test.
- payment-orchestrator is on the critical path of every payment and must scale horizontally (stateless instances, state in DB).
- When the outcome is unknown, funds stay reserved until resolution — never released optimistically.
