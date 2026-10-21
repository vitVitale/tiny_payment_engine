# UC-02 Verify payee before sending

## Goal
Before the payer authorises a transfer, tell them whether the payee name they entered matches the holder of the payee IBAN (Verification of Payee, required by Regulation (EU) 2024/886).

## Actors
- **Primary:** Payer (through a customer channel).
- **Supporting:** vop-service, Counterparty PSP (responding PSP), or our own vop-service when the payee is our customer.

## Preconditions
- The payer is authenticated.
- Payee IBAN and payee name have been entered.

## Trigger
Customer channel calls `POST /v1/payee-verifications` with payee IBAN and name.

## Main success scenario
1. payment-api validates the IBAN checksum and forwards the request to vop-service.
2. vop-service resolves the responding PSP from the IBAN (bank code → BIC routing directory).
3. If the payee is our own customer, vop-service answers locally ([UC-03](UC-03-answer-vop-request.md)); otherwise it sends a VoP request to the counterparty PSP.
4. The responding PSP returns one of: `MATCH`, `CLOSE_MATCH` (with the actual name), `NO_MATCH`, `NOT_POSSIBLE`.
5. vop-service stores the result with a `vopReference`, an expiry time and the values it was issued for: debtor account, payee IBAN and payee name.
6. payment-api returns the result and `vopReference` to the channel, which shows it to the payer.
7. The payer confirms and proceeds to [UC-01](UC-01-initiate-instant-credit-transfer.md) quoting the `vopReference`. payment-api accepts the reference only for a `pain.001` with the same debtor account, payee IBAN and payee name.

## Alternative and exception flows
- **1a. Invalid IBAN:** `400`, no VoP request is sent.
- **3a. Responding PSP does not answer within 5 s:** result `NOT_POSSIBLE`; the payer may still proceed.
- **6a. Result `CLOSE_MATCH` / `NO_MATCH` / `NOT_POSSIBLE`:** the channel must warn the payer that the payment may go to an account not held by the intended payee. The payer may still authorise.

## Postconditions
- A VoP result with a `vopReference` exists, is valid for a limited time (e.g. 30 min) and is bound to one debtor account, payee IBAN and payee name.

## ISO 20022 messages
None in SCT Inst itself; VoP uses the EPC VoP scheme API (JSON). Modelled as REST between vop-service and the simulated counterparty.

## Non-functional constraints
- End-to-end response ≤ 5 s (scheme requirement for VoP responses is a few seconds).
- Payee names are personal data: stored only as long as needed for the payment.

## Involved containers
payment-api, vop-service, csm-simulator (as the counterparty VoP endpoint).
