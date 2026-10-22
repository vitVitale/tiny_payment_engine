# 12 ledger-service

Architecture: [C4 components](../architecture/c4/03-components-ledger-service.puml),
[ADR-005](../architecture/decisions/ADR-005-ledger-booking-model.md), [ADR-009](../architecture/decisions/ADR-009-ledger-tombstones.md),
[ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md), [UC-10](../architecture/use-cases/UC-10-manage-accounts.md).

## 1. Responsibilities

- Accounts: open, block / unblock, close, read balances and entries; generate IBANs (UC-10).
- Double-entry journal: balanced journal entries and postings; booked balance of customer accounts.
- Memos: holds (outgoing) and pending credits (incoming) — create, settle / confirm, release / cancel.
- Account status checks inside the same transaction as the operation that needs them (ADR-005).
- Publish account lifecycle events (`accounts.events`).

Not responsible for: deciding whether a payment may proceed (orchestrator / risk), ISO reason codes (the
orchestrator maps ledger error codes), CSM liquidity, statements (`camt.053`, later).

## 2. Module layout

| Package | C4 component |
|---|---|
| `api.posting` | Posting API |
| `api.account` | Account API |
| `application.idempotency` | Idempotency guard |
| `application.reservation` | Reservation manager (holds, pending credits) |
| `application.account` | Account service |
| `domain.posting` | Posting engine (`JournalEntry`, `Posting`, balance rules) |
| `domain.account` | `Account`, `AccountStatus`, `Block`, IBAN generator |
| `infrastructure.persistence` | Account and journal repositories |
| `infrastructure.messaging` | outbox wiring |
| `infrastructure.jobs` | technical-account snapshot job |

## 3. REST API

All mutating endpoints require `Idempotency-Key` (see §6.1). Errors use the codes in §3.4.

### 3.1 Posting API (internal, caller: payment-orchestrator)

| Method & path | Purpose |
|---|---|
| `POST /v1/holds` | Create hold on the payer account |
| `POST /v1/holds/{holdId}/settle` | Hold → entry Dr payer / Cr CSM settlement account |
| `POST /v1/holds/{holdId}/settle-on-us` | Hold → entry Dr payer / Cr payee, with payee check |
| `POST /v1/holds/{holdId}/release` | Release hold (compensation) |
| `GET  /v1/holds/{holdId}` | Read hold (diagnostics, exception handling) |
| `POST /v1/pending-credits` | Create pending credit on the payee account |
| `POST /v1/pending-credits/{id}/confirm` | Pending credit → entry Dr CSM settlement account / Cr payee |
| `POST /v1/pending-credits/{id}/cancel` | Cancel pending credit |
| `GET  /v1/pending-credits/{id}` | Read pending credit |

`{id}` of a pending credit is the incoming payment's `paymentId` (UUID), not the `TxId`
([ADR-010](../architecture/decisions/ADR-010-incoming-transaction-identity.md)).

**Create hold**

```json
POST /v1/holds
Idempotency-Key: hold-create:0192f0e4-…

{ "holdId": "0192f0e4-…", "debtorIban": "DE…", "amountMinor": 12345, "currency": "EUR",
  "reference": { "paymentId": "0192f0e4-…", "txId": "TPE…" } }

201 Created
{ "holdId": "…", "accountId": "…", "status": "ACTIVE", "amountMinor": 12345, "createdAt": "…" }
```

Checks in one transaction: account exists (`ACCOUNT_NOT_FOUND`), is a customer account, has a `customer_id`
(`CUSTOMER_ID_MISSING`, §5), status `OPEN`
(`ACCOUNT_BLOCKED`, `ACCOUNT_CLOSED`), available balance ≥ amount (`INSUFFICIENT_FUNDS`); a hold with this id must not
be `RELEASED` (`HOLD_RELEASED`) or `SETTLED` (`HOLD_SETTLED`). An existing `ACTIVE` hold with the same id, account
and amount → `200` with the existing hold.

**Settle** — body `{}` → `200 { "holdId", "status": "SETTLED", "journalEntryId", "settledAt" }`. Does **not**
check the payer account status (the CSM has already settled; money left). `HOLD_NOT_FOUND`, `HOLD_RELEASED`.
Already `SETTLED` → `200` with the same result.

**Settle on-us** — body `{ "creditorIban": "DE…" }`. Checks payee account (exists, customer, has a `customer_id`, `OPEN`) in the same
transaction; on failure returns `422` with `ACCOUNT_NOT_FOUND` / `ACCOUNT_CLOSED` / `ACCOUNT_BLOCKED` and the hold
stays `ACTIVE` (the orchestrator releases it). On success: one entry Dr payer / Cr payee.
`200 { "holdId", "status": "SETTLED", "journalEntryId", "creditorAccountId", "settledAt" }`.

**Release** — body `{}` → `200 { "holdId", "status": "RELEASED" }`. If no hold with this id exists, a
**tombstone** is inserted (status `RELEASED`, no account, no amount) so a late `create` with the same id fails with
`HOLD_RELEASED`. Already `RELEASED` → `200`. `SETTLED` → `409 HOLD_SETTLED`.

**Create pending credit**

```json
POST /v1/pending-credits
Idempotency-Key: pending-credit-create:<paymentId>

{ "pendingCreditId": "<paymentId>", "creditorIban": "DE…", "amountMinor": 5000, "currency": "EUR",
  "reference": { "paymentId": "…", "txId": "…", "debtorAgentBic": "PEERDEFFXXX" } }

201 { "pendingCreditId": "…", "accountId": "…", "status": "ACTIVE", "createdAt": "…" }
```

Checks creditor account exists, customer, has a `customer_id`, `OPEN`, currency EUR — same transaction. Tombstone rules as for holds
(`PENDING_CREDIT_CANCELLED`, `PENDING_CREDIT_CONFIRMED`). No balance changes.

**Confirm** — `200 { "pendingCreditId", "status": "CONFIRMED", "journalEntryId", "accountId", "confirmedAt" }`.
Does not check account status (money has settled; a block restricts debits, not the booking).
`PENDING_CREDIT_NOT_FOUND`, `PENDING_CREDIT_CANCELLED`.

**Cancel** — `200 { "status": "CANCELLED" }`; unknown id → tombstone; `CONFIRMED` → `409 PENDING_CREDIT_CONFIRMED`.

### 3.2 Account API

Callers: back-office (`BACKOFFICE_OPERATOR`), compliance (`COMPLIANCE_OFFICER`), and risk-service (internal;
block / unblock with sanctions reasons). payment-api never calls the ledger.

| Method & path | Role | Purpose |
|---|---|---|
| `POST /v1/accounts` | BACKOFFICE_OPERATOR | Open account |
| `GET  /v1/accounts/{accountId}` | BACKOFFICE_OPERATOR | Account with booked / available balance |
| `GET  /v1/accounts?iban=…` | BACKOFFICE_OPERATOR | Lookup by IBAN |
| `GET  /v1/accounts/{accountId}/entries?from=&to=&cursor=` | BACKOFFICE_OPERATOR | Postings, newest first, cursor pagination (page 100) |
| `POST /v1/accounts/{accountId}/block` | BACKOFFICE_OPERATOR (`OPERATOR`), internal risk-service (`SANCTIONS*`) | Add block |
| `POST /v1/accounts/{accountId}/unblock` | same as block, per reason | Lift block of a reason |
| `POST /v1/accounts/{accountId}/close` | BACKOFFICE_OPERATOR | Close |
| `POST /v1/accounts/{accountId}/funding` | BACKOFFICE_OPERATOR, profiles `local`/`test` only | Test funding |
| `GET  /v1/customers/{customerId}/accounts` | internal risk-service | Accounts of a customer (for blocking all of them) |

**Open**

```json
POST /v1/accounts
{ "customerId": "CUST-000123",
  "holder": { "type": "PERSON", "name": "Anna Schmidt", "dateOfBirth": "1985-04-12",
              "countryOfResidence": "DE", "organisationId": null } }

201 { "accountId": "…", "iban": "DE…", "status": "OPEN", "bookedBalanceMinor": 0,
      "availableBalanceMinor": 0, "currency": "EUR", "openedAt": "…" }
```

`customerId` is mandatory: 1–50 chars `[A-Za-z0-9_-]`, the bank's customer number from the core system. Missing or
blank → `422 CUSTOMER_ID_MISSING`; malformed → `400 VALIDATION_FAILED` (field code `INVALID_CUSTOMER_ID`).
`type = ORGANISATION` requires `organisationId` (e.g. LEI) and no `dateOfBirth`.

**Block / unblock** — `{ "reason": "SANCTIONS_PROVISIONAL" | "SANCTIONS" | "OPERATOR", "comment": "…" }`.
Rules:
- Blocks are kept per reason; the account is `BLOCKED` while at least one block is active.
- Blocking with a reason already active is a no-op `200`.
- `SANCTIONS` replaces an active `SANCTIONS_PROVISIONAL` (one transaction: lift provisional, add sanctions).
- `SANCTIONS` and `SANCTIONS_PROVISIONAL` may be added / lifted only by the internal caller (risk-service) or a
  `COMPLIANCE_OFFICER`; a back-office operator gets `403 SANCTIONS_BLOCK_PROTECTED`.
- Closed accounts cannot be blocked / unblocked (`ACCOUNT_CLOSED`).

**Close** — allowed only if booked balance = 0, no `ACTIVE` holds, no `ACTIVE` pending credits; otherwise
`422 ACCOUNT_NOT_EMPTY`.

**Funding** — `{ "amountMinor": 100000, "currency": "EUR" }` → entry Dr FUNDING technical account / Cr customer.
In profile `prod` the controller bean does not exist → `404` (UC-10 3a).

### 3.3 Responses: balances

`bookedBalanceMinor` (sum of postings) and `availableBalanceMinor = booked − Σ active holds`. Pending credits are
listed separately (`pendingCreditsMinor`) and are never part of either balance.

### 3.4 Error codes

| HTTP | `code` | Orchestrator maps to |
|---|---|---|
| 422 | `ACCOUNT_NOT_FOUND` | `AC01` |
| 422 | `ACCOUNT_CLOSED` | `AC04` |
| 422 | `ACCOUNT_BLOCKED` | `AC06` |
| 422 | `ACCOUNT_NOT_CUSTOMER` | `AG01` |
| 422 | `INSUFFICIENT_FUNDS` | `AM04` |
| 422 | `CURRENCY_NOT_SUPPORTED` | `MS03` (cannot happen while everything is EUR; defensive) |
| 422 | `CUSTOMER_ID_MISSING` | `MS03` (technical reject: account without customer number, see §5) |
| 422 | `ACCOUNT_NOT_EMPTY` | — |
| 404 | `HOLD_NOT_FOUND`, `PENDING_CREDIT_NOT_FOUND`, `ACCOUNT_NOT_FOUND` (GET) | — |
| 409 | `HOLD_RELEASED`, `HOLD_SETTLED`, `PENDING_CREDIT_CANCELLED`, `PENDING_CREDIT_CONFIRMED` | see orchestrator |
| 409 | `IDEMPOTENCY_CONFLICT` | bug — alert |
| 409 | `CONCURRENT_MODIFICATION` | retry (returned only after internal retries are exhausted) |
| 403 | `SANCTIONS_BLOCK_PROTECTED` | — |

## 4. Messaging

Produces `accounts.events` via outbox ([02 §5](02-messaging-contracts.md#5-accountsevents-ledger--vop-risk)) in the same
transaction as the lifecycle change. 13 months after an account is closed a daily job publishes a tombstone
(`null` value) for its key ([ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md)).
Consumes nothing.

## 5. Data model

```sql
CREATE TABLE accounts (
  account_id      UUID PRIMARY KEY,
  iban            VARCHAR(34) NOT NULL UNIQUE,
  kind            VARCHAR(10) NOT NULL CHECK (kind IN ('CUSTOMER','TECHNICAL')),
  technical_code  VARCHAR(30) UNIQUE CHECK (technical_code IN ('CSM_SETTLEMENT','FUNDING')),
  customer_id     VARCHAR(50),
  holder_type     VARCHAR(12) CHECK (holder_type IN ('PERSON','ORGANISATION')),
  holder_name     VARCHAR(70),
  holder_dob      DATE,
  holder_country  CHAR(2),
  holder_org_id   VARCHAR(35),
  currency        CHAR(3) NOT NULL DEFAULT 'EUR' CHECK (currency = 'EUR'),
  status          VARCHAR(10) NOT NULL CHECK (status IN ('OPEN','BLOCKED','CLOSED')),
  booked_minor    BIGINT NOT NULL DEFAULT 0,   -- customer accounts only; technical: always 0, see snapshots
  held_minor      BIGINT NOT NULL DEFAULT 0,   -- Σ ACTIVE holds, customer accounts only
  version         BIGINT NOT NULL DEFAULT 0,   -- optimistic lock, customer accounts only
  revision        BIGINT NOT NULL DEFAULT 1,   -- lifecycle revision published in accounts.events
  opened_at       TIMESTAMPTZ NOT NULL,
  closed_at       TIMESTAMPTZ,
  CHECK (kind = 'TECHNICAL' OR (booked_minor >= 0 AND held_minor >= 0 AND held_minor <= booked_minor)),
  CHECK ((kind = 'TECHNICAL') = (technical_code IS NOT NULL)),
  CHECK (kind = 'TECHNICAL' OR customer_id IS NOT NULL)   -- Q-02: mandatory for customer accounts
);
CREATE INDEX accounts_customer ON accounts (customer_id);
CREATE SEQUENCE account_number_seq;

CREATE TABLE account_blocks (
  account_id  UUID NOT NULL REFERENCES accounts,
  reason      VARCHAR(30) NOT NULL CHECK (reason IN ('SANCTIONS','SANCTIONS_PROVISIONAL','OPERATOR')),
  created_at  TIMESTAMPTZ NOT NULL,
  created_by  VARCHAR(100) NOT NULL,
  comment     VARCHAR(500),
  lifted_at   TIMESTAMPTZ,
  lifted_by   VARCHAR(100)
);
CREATE UNIQUE INDEX account_blocks_active ON account_blocks (account_id, reason) WHERE lifted_at IS NULL;

CREATE TABLE journal_entries (
  entry_id    UUID PRIMARY KEY,
  entry_type  VARCHAR(20) NOT NULL CHECK (entry_type IN ('PAYMENT_OUT','PAYMENT_IN','ON_US','FUNDING')),
  payment_id  UUID,
  tx_id       VARCHAR(35),
  created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE postings (
  posting_id  BIGSERIAL PRIMARY KEY,
  entry_id    UUID NOT NULL REFERENCES journal_entries,
  account_id  UUID NOT NULL REFERENCES accounts,
  side        CHAR(2) NOT NULL CHECK (side IN ('DR','CR')),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX postings_account_time ON postings (account_id, posting_id DESC);

CREATE TABLE balance_snapshots (          -- technical accounts only
  account_id       UUID NOT NULL REFERENCES accounts,
  up_to_posting_id BIGINT NOT NULL,
  balance_minor    BIGINT NOT NULL,         -- Σ CR − Σ DR up to and including up_to_posting_id
  taken_at         TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (account_id, up_to_posting_id)
);

CREATE TABLE holds (
  hold_id      UUID PRIMARY KEY,
  account_id   UUID REFERENCES accounts,          -- NULL for tombstones
  amount_minor BIGINT CHECK (amount_minor > 0),   -- NULL for tombstones
  status       VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE','SETTLED','RELEASED')),
  payment_id   UUID,
  tx_id        VARCHAR(35),
  entry_id     UUID REFERENCES journal_entries,
  created_at   TIMESTAMPTZ NOT NULL,
  finalised_at TIMESTAMPTZ,
  tombstone    BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX holds_active_account ON holds (account_id) WHERE status = 'ACTIVE';

CREATE TABLE pending_credits (
  pending_credit_id UUID PRIMARY KEY,             -- incoming paymentId (ADR-010)
  debtor_agent_bic VARCHAR(11),
  tx_id        VARCHAR(35),
  account_id   UUID REFERENCES accounts,
  amount_minor BIGINT CHECK (amount_minor > 0),
  status       VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE','CONFIRMED','CANCELLED')),
  payment_id   UUID,
  entry_id     UUID REFERENCES journal_entries,
  created_at   TIMESTAMPTZ NOT NULL,
  finalised_at TIMESTAMPTZ,
  tombstone    BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX pending_credits_active_account ON pending_credits (account_id) WHERE status = 'ACTIVE';

CREATE TABLE idempotency_keys (
  idempotency_key VARCHAR(200) PRIMARY KEY,
  request_hash    CHAR(64) NOT NULL,     -- SHA-256 of canonical JSON body + path
  response_status INT NOT NULL,
  response_body   JSONB NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY, actor VARCHAR(100) NOT NULL, action VARCHAR(50) NOT NULL,
  account_id UUID, details JSONB NOT NULL, at TIMESTAMPTZ NOT NULL
);
```

Plus `outbox` from `messaging`. Seed migration creates the two technical accounts (`CSM_SETTLEMENT`, `FUNDING`)
with fixed UUIDs defined in `contracts` (`TechnicalAccounts.CSM_SETTLEMENT_ID`, …).

**Customer number:** every customer account has a `customer_id`; the `CHECK` constraint enforces it. The payment
operations still check it explicitly and refuse with `CUSTOMER_ID_MISSING`, so an account that somehow lacks one (data
migrated with the constraint added `NOT VALID`, a manual fix) can never move money without being visible to sanctions
screening, which works per customer. The orchestrator maps the code to the technical reason `MS03`.

**Balanced-entry guard:** a constraint trigger `DEFERRABLE INITIALLY DEFERRED` on `postings` checks at commit that
Σ DR = Σ CR for each touched `entry_id` and that each entry has ≥ 2 postings. The posting engine checks the same
before insert; the trigger is the safety net.

## 6. Behaviour

### 6.1 Idempotency guard

- Every mutating Posting / Account API request requires `Idempotency-Key` (≤ 200 chars) → `400 IDEMPOTENCY_KEY_MISSING`.
- In the business transaction: `INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING` is done *after* the
  business change with the response, so key and effect commit together.
- Before the business change: look up the key. Found with the same `request_hash` → replay stored status and body.
  Found with a different hash → `409 IDEMPOTENCY_CONFLICT`.
- Two concurrent requests with the same new key: the second insert conflicts at commit → that transaction is rolled
  back and the request re-runs the lookup, which then replays.
- Only `2xx` and `422` responses are stored (deterministic outcomes). `409` state conflicts and `5xx` are not.
- Retention 30 days (job deletes older rows hourly).
- Orchestrator key conventions: `hold-create:<holdId>`, `hold-settle:<holdId>`, `hold-settle-on-us:<holdId>`,
  `hold-release:<holdId>`, `pending-credit-create:<paymentId>`, `pending-credit-confirm:<paymentId>`,
  `pending-credit-cancel:<paymentId>`. Operations are also idempotent by resource state, so the key is a second line of
  defence and the place where a mismatching retry is detected.

### 6.2 Posting engine and locking

- **Customer accounts:** every operation that changes `booked_minor` or `held_minor` reads the account row, applies
  the change in memory and writes it with `WHERE account_id = ? AND version = ?`. On conflict the application
  service retries the whole transaction up to 5 times with 5–20 ms jitter, then `409 CONCURRENT_MODIFICATION`.
- **Technical accounts:** postings are inserted; the `accounts` row is never updated by postings (no version
  bump, no lock). This removes the hot row (ADR-005).
- Operations and their effects:

  | Operation | `held_minor` | `booked_minor` | Postings |
  |---|---|---|---|
  | create hold | payer +a | — | — |
  | release hold | payer −a | — | — |
  | settle hold | payer −a | payer −a | DR payer a, CR CSM_SETTLEMENT a |
  | settle on-us | payer −a | payer −a, payee +a | DR payer a, CR payee a |
  | create / cancel pending credit | — | — | — |
  | confirm pending credit | — | payee +a | DR CSM_SETTLEMENT a, CR payee a |
  | funding | — | payee +a | DR FUNDING a, CR customer a |

- Settle on-us touches two customer accounts: lock order is ascending `account_id` to avoid deadlocks (rows are
  read with `SELECT … FOR UPDATE` in that order inside the transaction, then version-checked).
- Status checks (`OPEN`, not blocked) read the account row in the same transaction as the change; because the block
  operation updates the same row (`revision`, `status`, `version`), a concurrent block either commits first (and
  the check sees it) or conflicts on `version`.

### 6.3 Technical account balance

`balance = snapshot.balance_minor + Σ(CR − DR) for postings with posting_id > snapshot.up_to_posting_id`.
Snapshot job (`PollingWorker`, every 15 min) takes a new snapshot per technical account using
`max(posting_id)` from a committed read. Note: `BIGSERIAL` ids can commit out of order; the job therefore snapshots
only up to `posting_id`s older than 60 s (`created_at < now() − 60 s`) so in-flight transactions cannot slip below
the snapshot boundary.

### 6.4 IBAN generation

`DE` + check digits + `tpe.bank.bank-code` (8 digits) + `nextval('account_number_seq')` zero-padded to 10 digits.
Check digits computed with mod 97-10. Generated IBANs MUST pass `Iban` validation.

## 7. Configuration

| Property | Default | Notes |
|---|---|---|
| `tpe.bank.bank-code` | `10010010` | must equal `ownBankCode` in the bank directory |
| `tpe.ledger.optimistic-retry.max-attempts` | `5` | |
| `tpe.ledger.idempotency.retention` | `30d` | |
| `tpe.ledger.snapshot.interval` | `15m` | |
| `tpe.ledger.funding.enabled` | `true` in `local`/`test`, `false` in `prod` | `prod` startup fails if `true` |

## 8. Non-functional requirements

- p99 ≤ 50 ms for create hold / settle / release / pending-credit operations at 300 operations/s (service-side,
  excluding network). These calls are on the 1 s internal budget of UC-04.
- No lost updates: a load test with 50 concurrent holds on one account never lets `available` go below 0.
- All admin actions write `audit_log` (actor = JWT `sub`, or `risk-service` for internal calls).

## 9. Acceptance criteria

- **LED-AC-01** Opening an account returns a valid IBAN with our bank code and publishes `AccountOpened` with
  `revision = 1`. (UC-10 1–2)
- **LED-AC-02** A hold reduces available balance but not booked balance; release restores available balance. (ADR-005)
- **LED-AC-03** A hold larger than the available balance fails with `INSUFFICIENT_FUNDS` and changes nothing. (UC-04 3a)
- **LED-AC-04** Releasing an unknown hold creates a tombstone; a later create with that id fails `HOLD_RELEASED`.
- **LED-AC-05** Settle posts DR payer / CR CSM settlement account and leaves no `ACTIVE` hold; repeating it returns
  the same `journalEntryId`. (UC-04 8)
- **LED-AC-06** Settle on-us with a blocked payee fails `ACCOUNT_BLOCKED` and leaves the hold `ACTIVE`. (UC-04 4a-1)
- **LED-AC-07** A pending credit changes neither booked nor available balance; confirm adds to both; cancel removes
  the memo. (ADR-005)
- **LED-AC-08** Creating a pending credit on a blocked / closed / unknown account fails with the matching code. (UC-06 4a)
- **LED-AC-09** A block committed concurrently with a pending-credit creation is never "missed": either the credit
  is refused, or the block commits after it (verified with a concurrency test using two transactions and a latch).
- **LED-AC-10** 200 concurrent settlements of different payers do not conflict on the CSM settlement account (no
  `CONCURRENT_MODIFICATION`, zero version conflicts recorded on technical accounts). (ADR-005)
- **LED-AC-11** The derived CSM settlement account balance equals Σ(CR − DR) over all its postings after snapshots
  are taken while postings are being inserted.
- **LED-AC-12** A journal entry whose postings do not balance cannot be committed (trigger test bypassing the engine).
- **LED-AC-13** A back-office operator cannot lift a `SANCTIONS` block (`403`); risk-service can. (UC-10, UC-09)
- **LED-AC-14** Closing an account with a balance, active hold or active pending credit fails `ACCOUNT_NOT_EMPTY`.
- **LED-AC-15** The funding endpoint returns `404` in profile `prod`. (UC-10 3a)
- **LED-AC-16** Same `Idempotency-Key` with a different body → `409 IDEMPOTENCY_CONFLICT`; same body → identical
  stored response.
- **LED-AC-17** Opening an account without `customerId` fails `422 CUSTOMER_ID_MISSING`; a customer account row
  without `customer_id` (inserted bypassing the constraint in a test) makes hold and pending-credit creation fail
  `CUSTOMER_ID_MISSING`. (Q-02)
- **LED-AC-18** Every admin action (open, fund, block, unblock, close) writes one `audit_log` row with the actor
  (JWT `sub`, or `risk-service` for internal calls), action, account id and time, in the same transaction as the
  change; a refused action (e.g. `ACCOUNT_NOT_EMPTY`, `403` on a sanctions unblock) changes nothing and writes no
  success record. (UC-10 NFR)
