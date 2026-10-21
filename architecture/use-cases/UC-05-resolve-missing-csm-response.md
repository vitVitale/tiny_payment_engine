# UC-05 Resolve missing CSM response

## Goal
Determine the real outcome of an outgoing payment for which no `pacs.002` arrived before the 20 s time-out deadline, and finalise the ledger accordingly.

## Actors
- **Primary:** Scheduler (deadline timer in payment-orchestrator).
- **Supporting:** csm-gateway, CSM, ledger-service, back-office operator (last resort).

## Preconditions
- Payment in state `SENT_TO_CSM`, hold active, deadline reached.

## Trigger
The deadline timer for the payment fires.

## Main success scenario
1. payment-orchestrator moves the payment to `INVESTIGATING` and publishes a `RequestPaymentStatus` command (via outbox).
2. csm-gateway consumes the command. Because both commands are keyed by `TxId`, it has already handled the earlier `SendCreditTransfer` and knows from its journal whether the `pacs.008` was sent.
3. The `pacs.008` was sent: csm-gateway sends a `pacs.028` for the original `TxId` to the CSM.
4. The CSM answers with a `pacs.002` containing the final status.
5. csm-gateway publishes `CreditTransferSettled` or `CreditTransferRejected`.
6. payment-orchestrator settles or releases the hold, as in [UC-04](UC-04-execute-outgoing-payment.md) steps 8 / 6a. State → `SETTLED` or `REJECTED`.
7. The payer is notified.

## Alternative and exception flows
- **2a. The `pacs.008` was never sent** (send-by time passed, see [UC-04](UC-04-execute-outgoing-payment.md) 5a): csm-gateway answers `CreditTransferNotSent` without contacting the CSM; payment-orchestrator releases the hold. State → `REJECTED` with `AB03`.
- **4a. No answer to `pacs.028`:** retry with exponential back-off (e.g. 5 s, 15 s, 60 s, then every 5 min).
- **4b. Still unresolved after the retry budget (e.g. 1 h):** create an exception case for the back office; the hold stays in place; the payer sees status `PENDING`.
- **4c. The CSM reports the original `pacs.008` as unknown:** safe to treat as rejected (`AB05`) and release the hold, because csm-gateway confirmed it sent the message before the send-by time and the time-out deadline has passed, so the CSM rejects the message if it arrives late. Any contradicting `pacs.002` that still arrives is handled by [UC-04](UC-04-execute-outgoing-payment.md) 8a.
- **Late `pacs.002` arrives during investigation:** processed normally; any pending `pacs.028` answer becomes a no-op.

## Postconditions
- Payment is finalised (`SETTLED` / `REJECTED`) or an exception case exists. Funds are never released while the outcome is unknown.

## ISO 20022 messages
`pacs.028` (out), `pacs.002` (in).

## Non-functional constraints
- Timers are persistent (survive restarts) and evaluated by a single owner per payment (DB-based scheduling with row locking).
- The decision "never sent" is taken only by csm-gateway, from its journal ([ADR-004](../decisions/ADR-004-csm-gateway-owns-scheme-timing.md)).

## Involved containers
payment-orchestrator, csm-gateway, ledger-service, csm-simulator.
