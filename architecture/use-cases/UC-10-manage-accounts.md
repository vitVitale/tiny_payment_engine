# UC-10 Manage accounts and balances

## Goal
Allow back-office operators to open, block, unblock and close customer accounts and to fund them for testing; allow anyone authorised to read balances and journal history.

## Actors
- **Primary:** Back-office operator.
- **Supporting:** ledger-service.

## Preconditions
- The operator is authenticated with a back-office role.

## Trigger
Back-office calls to ledger-service admin API.

## Main success scenario (open and fund an account)
1. Operator calls `POST /v1/accounts` with holder data; ledger-service generates an IBAN with our bank code and creates an account with zero balance.
2. ledger-service publishes `AccountOpened` (consumed by vop-service and risk-service projections).
3. Operator calls `POST /v1/accounts/{id}/funding` (test environments only); ledger-service posts a journal entry debiting the technical *funding* account and crediting the customer account.
4. Operator reads `GET /v1/accounts/{id}` (booked and available balance) and `GET /v1/accounts/{id}/entries`.

## Alternative and exception flows
- **Block / unblock:** `POST /v1/accounts/{id}/block` / `unblock` with a reason (e.g. `SANCTIONS`, `SANCTIONS_PROVISIONAL`, `OPERATOR`); publishes `AccountBlocked` / `AccountUnblocked`. Sanctions blocks can be lifted only by risk-service or a compliance officer.
- **Close:** allowed only with zero balance and no active holds or pending credits; publishes `AccountClosed`.
- **3a. Funding endpoint called in production profile:** `404`.

## Postconditions
- Every balance change is a balanced journal entry; balances are derived from entries. Holds and pending credits are memos and never change the booked balance ([ADR-005](../decisions/ADR-005-ledger-booking-model.md)).

## ISO 20022 messages
None (`camt.053` statements — later).

## Non-functional constraints
- All admin actions are audited (who, when, what).

## Involved containers
ledger-service (and projections in vop-service, risk-service).
