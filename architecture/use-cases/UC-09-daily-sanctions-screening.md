# UC-09 Daily sanctions screening of customers

## Goal
Comply with Regulation (EU) 2024/886: instead of screening each instant payment against EU sanctions lists, screen the whole customer base at least daily and immediately after a list update, and block accounts of listed persons.

## Actors
- **Primary:** Scheduler.
- **Supporting:** risk-service, Sanctions list provider, Compliance officer, ledger-service.

## Preconditions
- risk-service holds a projection of account holders (fed by ledger-service events).

## Trigger
Daily schedule, or a new sanctions list version published by the provider.

## Main success scenario
1. risk-service downloads the current sanctions list and stores it as a new version.
2. risk-service matches every account holder (name, date of birth, country, identifiers) against the list.
3. For each potential hit above the review threshold, risk-service creates a review case for the compliance officer.
4. For an exact or high-confidence hit (above the freeze threshold), risk-service immediately requests ledger-service to block the holder's accounts provisionally (`POST /v1/accounts/{id}/block`, reason `SANCTIONS_PROVISIONAL`), without waiting for the review.
5. The compliance officer confirms a true hit.
6. risk-service requests ledger-service to block the holder's accounts (`POST /v1/accounts/{id}/block`, reason `SANCTIONS`), replacing any provisional block.
7. From now on, outgoing and incoming payments for these accounts are rejected with `AC06` by the account status check in [UC-04](UC-04-execute-outgoing-payment.md) and [UC-06](UC-06-receive-incoming-payment.md).

## Alternative and exception flows
- **5a. False positive:** compliance officer dismisses the case; any provisional block is lifted (`POST /v1/accounts/{id}/unblock`); the decision is remembered for the same list entry and customer data.
- **1a. Provider unavailable:** keep the previous list, alert operations; retry hourly.

## Postconditions
- Screening run recorded with list version, number of customers screened and hits.

## ISO 20022 messages
None.

## Non-functional constraints
- A full run over the customer base finishes within 1 h; a run triggered by a list update starts as soon as the new version is stored.
- Provisional blocks are applied within minutes of the list update, so listed persons cannot transact while their case waits for review.
- Payment path never calls the sanctions provider synchronously.
- Per-payment screening against EU lists is not done for SCT Inst (Regulation (EU) 2024/886). risk-service may still screen individual payments against non-EU lists, which is the only source of a per-payment `RR04`.

## Involved containers
risk-service, ledger-service.
