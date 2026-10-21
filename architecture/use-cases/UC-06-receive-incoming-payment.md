# UC-06 Receive incoming instant payment

## Goal
As Beneficiary PSP, accept or reject an incoming SCT Inst payment within the scheme time limit and make the funds available to the payee immediately after settlement.

## Actors
- **Primary:** CSM.
- **Supporting:** csm-gateway, payment-orchestrator (incoming saga), risk-service, ledger-service, notification-service.

## Preconditions
- csm-gateway is connected to the CSM.

## Trigger
The CSM delivers a `pacs.008` addressed to our BIC.

## Main success scenario
1. csm-gateway validates the `pacs.008` against the XSD, journals it, deduplicates by `MsgId` / `TxId`, records the receipt time and the **answer deadline** (receipt + 5 s, configurable) and publishes `CreditTransferReceived` to `csm.inbound.events`.
2. payment-orchestrator creates an incoming payment in state `RECEIVED` (idempotent by `TxId`).
3. payment-orchestrator calls risk-service for an incoming assessment; result `ALLOW`.
4. payment-orchestrator registers a **pending credit** with ledger-service (`POST /v1/pending-credits`, id = `TxId`). In the same transaction ledger-service checks the creditor account (exists, open, not blocked, accepts EUR) and records the pending credit as a memo: no journal entry, booked and available balance unchanged.
5. payment-orchestrator publishes the verdict `AcceptCreditTransfer` to `csm.outbound.commands`. State → `ACCEPT_REQUESTED`.
6. csm-gateway, which is the single owner of the answer to the CSM, sends `pacs.002` `ACCP` because the verdict arrived before the answer deadline and no answer has been sent yet. It journals the answer and publishes `AnswerSent(ACCP)`.
7. payment-orchestrator consumes `AnswerSent(ACCP)`. State → `ACCEPTED`.
8. The CSM settles and sends the settlement confirmation (`pacs.002` `ACSC`) to csm-gateway, which publishes `CreditTransferSettled`.
9. payment-orchestrator confirms the pending credit: ledger-service books Dr CSM settlement account / Cr payee and removes the memo; funds are available. State → `SETTLED`.
10. payment-orchestrator publishes `PaymentSettled`; notification-service informs the payee ([UC-08](UC-08-notify-customer.md)).

## Alternative and exception flows
- **1a. Invalid message (schema):** csm-gateway sends `pacs.002` `RJCT` `FF01` directly; no payment is created.
- **1b. Duplicate `pacs.008`:** if an answer was already sent, csm-gateway re-sends it; otherwise the duplicate is dropped (the original answer deadline keeps running). No second credit.
- **3a. Risk `DENY`:** payment-orchestrator publishes `RejectCreditTransfer` with `FR01` (fraud rules) or `RR04` (non-EU sanctions lists only, see [UC-09](UC-09-daily-sanctions-screening.md)). No pending credit is created.
- **4a. Account unknown / closed / blocked:** ledger-service refuses the pending credit with the reason; payment-orchestrator publishes `RejectCreditTransfer` with `AC01` / `AC04` / `AC06`.
- **6a. Rejection verdict:** csm-gateway sends `pacs.002` `RJCT` with the given reason and publishes `AnswerSent(RJCT)`; payment-orchestrator cancels the pending credit, if any. State → `REJECTED`.
- **6b. Answer deadline reached before any verdict:** csm-gateway sends `pacs.002` `RJCT` `AB05` on its own and publishes `AnswerSent(RJCT, AB05)`. A verdict arriving later is ignored by csm-gateway. payment-orchestrator cancels the pending credit, if any. State → `REJECTED`.
- **8a. CSM rejects after our `ACCP` (e.g. originator timed out):** csm-gateway publishes `CreditTransferRejected`; payment-orchestrator cancels the pending credit. State → `REJECTED`.
- **8b. No settlement confirmation before the settlement timer fires:** state → `INVESTIGATING`; the pending credit stays; payment-orchestrator requests status and resolves like [UC-05](UC-05-resolve-missing-csm-response.md) (settled → step 9, rejected → 8a).

## Postconditions
- **Success:** payee credited and funds available; CSM settlement account debited; payee notified.
- **Failure:** no credit and no pending credit on the payee account; exactly one negative `pacs.002` was sent.
- **Invariant:** exactly one answer (`ACCP` or `RJCT`) is sent per `TxId`, and payment-orchestrator's state always follows the answer csm-gateway actually sent ([ADR-004](../decisions/ADR-004-csm-gateway-owns-scheme-timing.md)).

## ISO 20022 messages
`pacs.008` (in), `pacs.002` (out: `ACCP` / `RJCT`; in: `ACSC` / `RJCT`), `pacs.028` (out, investigation), `camt.054` (to payee).

## Non-functional constraints
- Steps 1–6 ≤ 2 s p99 (the Beneficiary PSP's share of the 10 s budget); the 5 s answer deadline is the hard stop.
- Incoming path must not depend on vop-service or notification-service availability.

## Involved containers
csm-gateway, payment-orchestrator, risk-service, ledger-service, notification-service, csm-simulator.
