# UC-04 Execute outgoing payment

## Goal
Move the money of an accepted payment from the payer to the payee's PSP through the CSM, within the SCT Inst time limits, and leave the ledger consistent whatever the outcome.

## Actors
- **Primary:** payment-orchestrator (outgoing saga).
- **Supporting:** risk-service, ledger-service, csm-gateway, CSM, notification-service.

## Preconditions
- A payment exists in state `RECEIVED` ([UC-01](UC-01-initiate-instant-credit-transfer.md)).

## Trigger
payment-orchestrator starts the outgoing saga for the payment.

## Main success scenario
1. payment-orchestrator schedules the payment deadline: acceptance time stamp + 20 s.
2. payment-orchestrator calls risk-service `POST /v1/risk/assessments` (REST); risk-service returns `ALLOW`. State → `RISK_CHECKED`.
3. payment-orchestrator calls ledger-service `POST /v1/holds` with `holdId = paymentId`; ledger-service reserves the amount on the payer account. State → `FUNDS_RESERVED`.
4. If the payee account is held at our bank, continue with **4a (on-us)**. Otherwise payment-orchestrator writes a `SendCreditTransfer` command to its outbox, carrying a **send-by** time (acceptance time stamp + 10 s, configurable, always before the 20 s deadline); the outbox relay publishes it to Kafka topic `csm.outbound.commands`. State → `SENT_TO_CSM`.
5. csm-gateway consumes the command, checks that the send-by time has not passed, builds a `pacs.008` with BAH, validates it against the XSD, journals it as *sent* and sends it to the CSM.
6. The CSM forwards the `pacs.008` to the Beneficiary PSP, gets its positive answer, settles and sends `pacs.002` with `ACSC` to csm-gateway.
7. csm-gateway journals the `pacs.002`, matches it by `TxId` and publishes `CreditTransferSettled` to `csm.inbound.events`.
8. payment-orchestrator consumes the event (inbox dedup by `TxId` + outcome), calls ledger-service `POST /v1/holds/{holdId}/settle`: debit payer, credit CSM settlement account. State → `SETTLED`. Deadline timer cancelled.
9. payment-orchestrator publishes `PaymentSettled` to `payments.events`; notification-service informs the payer ([UC-08](UC-08-notify-customer.md)).

## Alternative and exception flows
- **2a. risk-service returns `DENY`:** state → `REJECTED` with `FR01` (fraud rules) or `RR04` (non-EU sanctions lists only; EU-list screening is done per customer in [UC-09](UC-09-daily-sanctions-screening.md), not per payment). No ledger action. Notify payer.
- **2b. risk-service returns `REVIEW`:** treated as `DENY` for instant payments (no time for manual review), reason `FR01`.
- **2c. risk-service unavailable / timeout (> 500 ms):** retry once, then reject with `FR01` — fail closed.
- **3a. Insufficient available balance:** ledger returns `INSUFFICIENT_FUNDS`; state → `REJECTED` with `AM04`.
- **3b. Payer account blocked / closed:** state → `REJECTED` with `AC06` / `AC04`.
- **4a. On-us payment (payee at our bank):** skip the CSM. payment-orchestrator runs the incoming risk assessment for the payee, then calls ledger-service `POST /v1/holds/{holdId}/settle-on-us` with the payee account. In one transaction ledger-service checks the payee account (exists, open, not blocked) and posts a single journal entry debiting the payer and crediting the payee. State `FUNDS_RESERVED` → `SETTLED`. Notify payer and payee.
- **4a-1. On-us payee account unknown / closed / blocked, or payee risk `DENY`:** release the hold. State → `REJECTED` with `AC01` / `AC04` / `AC06` / `FR01`.
- **5a. Send-by time already passed** (relay lag, csm-gateway was down): csm-gateway does not send the `pacs.008`, journals it as *not sent* and publishes `CreditTransferNotSent`; payment-orchestrator releases the hold. State → `REJECTED` with `AB03`.
- **6a. CSM or Beneficiary PSP rejects (`pacs.002` `RJCT`, e.g. `AC01`, `AC04`, `AB05`):** csm-gateway publishes `CreditTransferRejected`; payment-orchestrator releases the hold (compensation). State → `REJECTED` with the received reason code.
- **6b. No `pacs.002` before the 20 s deadline:** continue with [UC-05](UC-05-resolve-missing-csm-response.md).
- **8a. `pacs.002` arrives after the payment was already finalised:** if it confirms the final state — ignore; if it contradicts (e.g. `ACSC` after we released) — raise an operational incident for the back office (no automatic money movement).
- **Any step: payment-orchestrator crashes:** the saga resumes from the persisted state; every downstream call is idempotent (`holdId`, `TxId`, message ids).

## Postconditions
- **Success:** payer debited, CSM settlement account (or on-us payee) credited, payment `SETTLED`, payer notified.
- **Failure:** no hold remains on the payer account, payment `REJECTED` with an ISO reason code, payer notified.
- **Invariant:** a payment never ends with both a released hold and a settled debit; a `pacs.008` is never sent after its send-by time ([ADR-004](../decisions/ADR-004-csm-gateway-owns-scheme-timing.md)).

## ISO 20022 messages
`pacs.008` (out), `pacs.002` (in), `pain.002` (to payer, on request).

## Non-functional constraints
- Internal processing (steps 2–5) ≤ 1 s p99 so that the 10 s target is met with CSM and Beneficiary PSP time.
- Synchronous calls carry explicit timeouts derived from the remaining deadline.
- Exactly-once effect: outbox on the producer side, inbox dedup on the consumer side, idempotent ledger operations.

## Involved containers
payment-orchestrator, risk-service, ledger-service, csm-gateway, notification-service, csm-simulator (as CSM).
