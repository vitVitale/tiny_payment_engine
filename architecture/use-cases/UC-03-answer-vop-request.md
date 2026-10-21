# UC-03 Answer VoP request from another PSP

## Goal
As the responding PSP, tell a requesting PSP whether a name matches the holder of an IBAN held at our bank.

## Actors
- **Primary:** Counterparty PSP (requesting PSP).
- **Supporting:** vop-service, ledger-service (account holder data).

## Preconditions
- The requesting PSP is a registered participant of the VoP scheme (authenticated by mTLS in reality; simulated here).

## Trigger
Inbound `POST /vop/v1/verifications` to vop-service with IBAN and name.

## Main success scenario
1. vop-service authenticates the requesting PSP.
2. vop-service looks up the account holder name for the IBAN (from its replicated account-holder projection fed by ledger-service events).
3. vop-service normalises both names (case, diacritics, legal-form suffixes, word order) and computes a similarity score.
4. vop-service maps the score to `MATCH` / `CLOSE_MATCH` / `NO_MATCH`; for `CLOSE_MATCH` it returns the actual holder name.
5. vop-service logs the request for audit and returns the result.

## Alternative and exception flows
- **2a. IBAN unknown or account closed:** result `NOT_POSSIBLE` (no hint whether the account exists beyond what the scheme allows).
- **3a. Account holder is a legal entity and the request carries an organisation identifier:** match on the identifier instead of the name.

## Postconditions
- An audit record of the request and response exists.

## ISO 20022 messages
None (EPC VoP API).

## Non-functional constraints
- p99 response ≤ 1 s.
- Lookups read only from the local projection — no synchronous call to ledger-service.

## Involved containers
vop-service, ledger-service (event source for account-holder projection).
