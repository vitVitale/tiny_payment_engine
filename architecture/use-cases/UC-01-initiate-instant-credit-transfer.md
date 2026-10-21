# UC-01 Initiate instant credit transfer

## Goal
The payer instructs our bank to send an SCT Inst payment to a payee at any SEPA PSP (including our own bank).

## Actors
- **Primary:** Payer (through a customer channel acting as API client).
- **Supporting:** payment-orchestrator, vop-service.

## Preconditions
- The payer is authenticated by the customer channel; the API client holds a valid access token.
- The payer owns an open EUR account at our bank.
- The payer has run Verification of Payee ([UC-02](UC-02-verify-payee-outgoing.md)) and confirmed the result.

## Trigger
Customer channel calls `POST /v1/payments` with a `pain.001` payload (JSON representation or ISO 20022 XML) and an `Idempotency-Key` header.

## Main success scenario
1. payment-api authenticates the client and authorises access to the debtor account.
2. payment-api checks the `Idempotency-Key`; no earlier request with this key exists.
3. payment-api validates the payload: XML against the `pain.001` XSD (or JSON against the OpenAPI schema), IBAN checksums, BIC format, amount > 0 with at most 2 decimals, currency `EUR`, presence of `EndToEndId`, VoP reference.
4. payment-api checks the VoP reference with vop-service: it exists, has not expired and was issued for the same debtor account, payee IBAN and payee name as in the `pain.001`.
5. payment-api derives a deterministic `commandId` from the API client id and the `Idempotency-Key` and forwards a `SubmitPayment` command carrying it to payment-orchestrator.
6. payment-orchestrator checks that no payment exists for the `commandId`, assigns `paymentId`, `TxId` and `UETR`, persists the payment in state `RECEIVED` with the `commandId` (unique constraint) and records the acceptance time stamp.
7. payment-orchestrator starts the outgoing saga ([UC-04](UC-04-execute-outgoing-payment.md)) asynchronously.
8. payment-api stores the response for the `Idempotency-Key` and returns `202 Accepted` with `paymentId`, status `RECEIVED` and a `Location` header for status polling.

## Alternative and exception flows
- **1a. Debtor account not owned by the caller:** `403 Forbidden`.
- **2a. Same `Idempotency-Key`, same payload:** payment-api returns the original response (same `paymentId`, current status). No new payment.
- **2b. Same `Idempotency-Key`, different payload:** `422 Unprocessable Entity`, error `IDEMPOTENCY_KEY_REUSED`.
- **3a. Schema or field validation fails:** `400 Bad Request` with field errors; a `pain.002` with `RJCT` / `FF01` is available for XML clients.
- **4a. Missing, expired or mismatching VoP reference:** `422`, error `VOP_REQUIRED`.
- **5a. payment-orchestrator unavailable or the call times out:** `503 Service Unavailable` with `Retry-After`; no response is stored for the key. The client retries with the same `Idempotency-Key`, which yields the same `commandId`.
- **6a. A payment already exists for the `commandId`** (the earlier attempt reached payment-orchestrator but its response was lost): payment-orchestrator returns the existing `paymentId` and current status. No new payment, no rejection.

The `EndToEndId` is assigned by the payer, is not required to be unique and is often `NOTPROVIDED`, so it is not used for duplicate detection. Duplicate submissions are detected only through the `Idempotency-Key` / `commandId`.

## Postconditions
- **Success:** exactly one payment exists per `commandId`, in state `RECEIVED`; the outgoing saga has started. No funds are moved yet.
- **Failure:** no payment is created.

## ISO 20022 messages
`pain.001` (in), `pain.002` (out, on request or rejection).

## Non-functional constraints
- p99 latency of this call ≤ 300 ms; the 10 s scheme clock starts at step 6.
- Idempotency keys (payment-api) and `commandId`s (payment-orchestrator) are retained for at least 24 h.

## Involved containers
payment-api, vop-service, payment-orchestrator.
