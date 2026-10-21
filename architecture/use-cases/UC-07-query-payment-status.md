# UC-07 Query payment status

## Goal
Let the payer see the current status of a payment they initiated.

## Actors
- **Primary:** Payer (through a customer channel).
- **Supporting:** payment-orchestrator.

## Preconditions
- The payment was created by [UC-01](UC-01-initiate-instant-credit-transfer.md).

## Trigger
`GET /v1/payments/{paymentId}` (JSON) or `GET /v1/payments/{paymentId}/pain.002` (XML).

## Main success scenario
1. payment-api authenticates the caller and checks they own the debtor account of the payment.
2. payment-api reads the payment status from payment-orchestrator.
3. payment-api maps the internal state to the external status:
   `RECEIVED`, `RISK_CHECKED`, `FUNDS_RESERVED`, `SENT_TO_CSM`, `INVESTIGATING` → `PDNG`; `SETTLED` → `ACSC`; `REJECTED` → `RJCT` + reason code.
4. payment-api returns the status (JSON or `pain.002`).

## Alternative and exception flows
- **1a. Payment not found or not owned by the caller:** `404 Not Found` (no information leak).

## Postconditions
None (read-only).

## ISO 20022 messages
`pain.002` (out).

## Non-functional constraints
- p99 ≤ 200 ms. Clients should prefer webhooks ([UC-08](UC-08-notify-customer.md)) over polling.

## Involved containers
payment-api, payment-orchestrator.
