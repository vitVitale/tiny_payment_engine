# ADR-004 csm-gateway owns scheme timing at the CSM boundary

- **Status:** Accepted
- **Date:** 2026-10-21

## Context
Between payment-orchestrator and the CSM there are an outbox relay, Kafka and csm-gateway. Each hop can lag or be down.
payment-orchestrator measures time from its own events, but only csm-gateway knows when a message really left or arrived.
Two races follow from this:

- **Outgoing:** the 20 s deadline fires and the hold is released while a `SendCreditTransfer` command is still on its way;
  csm-gateway then sends the `pacs.008` and the money leaves without a debit of the payer.
- **Incoming:** the ~5 s answer deadline has no single owner. If payment-orchestrator and csm-gateway can both decide,
  a late `ACCP` can follow a timeout `RJCT`, or a pending credit stays for a payment we rejected.

## Decision
- csm-gateway is the **only component that decides what is sent to the CSM and when**. payment-orchestrator sends intentions
  (commands and verdicts) and follows the facts csm-gateway reports.
- **Outgoing send-by guard:** every `SendCreditTransfer` carries a `sendBy` time (acceptance + 10 s by default, always before the
  20 s deadline). csm-gateway journals the `pacs.008` as *sent* before transmitting it and never sends it after `sendBy`.
  When `sendBy` has passed it journals *not sent* and publishes `CreditTransferNotSent` (reason `AB03`).
- **Status requests:** `RequestPaymentStatus` uses the same partition key (`TxId`) as `SendCreditTransfer`, so csm-gateway always
  handles the send first. If the journal says *not sent*, it answers `CreditTransferNotSent` without a `pacs.028`. A CSM answer
  "unknown" is final only for a message the journal shows as sent before `sendBy`, once the 20 s deadline has passed.
- **Incoming answer deadline:** csm-gateway records the receipt time of each `pacs.008` and its answer deadline (receipt + 5 s by default).
  It sends exactly one answer per `TxId`: the orchestrator's verdict (`AcceptCreditTransfer` / `RejectCreditTransfer`) if it arrives
  in time, otherwise `RJCT` `AB05` on its own. Later verdicts are ignored. It then publishes `AnswerSent(ACCP | RJCT, reason)`.
- payment-orchestrator moves an incoming payment to `ACCEPTED` only on `AnswerSent(ACCP)` and cancels the pending credit on `AnswerSent(RJCT)`.

## Options considered
| Option | Assessment |
|---|---|
| A. Orchestrator owns all deadlines (status quo) | Simple, but cannot see relay / Kafka / gateway lag, so both races stay open. |
| B. Distributed lock or synchronous REST call orchestrator → gateway | Removes the Kafka hop but adds coupling and a new failure mode on the hot path. |
| **C. Gateway owns the CSM boundary, orchestrator follows facts** | One decision point next to the clock that matters; fits the existing outbox / inbox flow. |

## Consequences
- The two races are closed by construction: a `pacs.008` is never sent after `sendBy`, and exactly one answer is sent per incoming `TxId`.
- csm-gateway holds per-payment state (journal, answer deadlines) and needs its own persistent timers, the same way as payment-orchestrator ([ADR-003](ADR-003-orchestrated-saga.md)).
- New events on `csm.inbound.events`: `CreditTransferNotSent`, `AnswerSent`. The incoming saga gets the state `ACCEPT_REQUESTED`.
- The `sendBy` and answer-deadline values must be tuned against the measured relay and Kafka latency (ADR-002).
