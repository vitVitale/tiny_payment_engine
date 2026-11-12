# 13 vop-service

Architecture: [C4 components](../architecture/c4/03-components-vop-service.puml),
[UC-02](../architecture/use-cases/UC-02-verify-payee-outgoing.md), [UC-03](../architecture/use-cases/UC-03-answer-vop-request.md),
[UC-01](../architecture/use-cases/UC-01-initiate-instant-credit-transfer.md) 4.

## 1. Responsibilities

- **Requesting PSP** (UC-02): route a verification to the responding PSP (or answer locally for our own customers),
  store the result as a `vopReference` bound to debtor account, payee IBAN and payee name.
- **Responding PSP** (UC-03): answer verification requests from other PSPs from a local account-holder projection.
- Check a `vopReference` for payment-api (UC-01 4).
- Purge personal data when it is no longer needed.

Not responsible for: blocking payments (payment-api decides on the reference check), account data ownership (ledger).

## 2. Module layout

| Package | C4 component |
|---|---|
| `api.verify` | Payee verification API (internal) |
| `api.responder` | VoP responder API (scheme endpoint) |
| `api.accounts` | Account events listener |
| `application.accounts` | Account-holder projection (the listener's transaction and inbox) |
| `application.requesting` | Requesting service |
| `application.responding` | Responding service |
| `application.references` | VoP reference store |
| `application.retention` | Retention job (the handler) |
| `application.metrics` | `tpe.vop.requests` port |
| `domain.accounts` | Account holder as projected |
| `domain.matching` | Name matcher, `HolderVerifier` |
| `domain.references` | Issued reference and its check |
| `domain.routing` | Routing directory (wraps `BankDirectory`) |
| `infrastructure.peer` | Counterparty VoP client (until the remote track: an adapter answering `NOT_POSSIBLE`) |
| `infrastructure.jobs` | Retention `PollingWorker`, `tpe.vop.retention.overdue` |
| `infrastructure.metrics` | Micrometer `tpe.vop.requests` |
| `infrastructure.persistence` | Repositories |

## 3. REST API

### 3.1 Internal (caller: payment-api)

**`POST /v1/verifications`** (UC-02)

```json
{ "debtorIban": "DE…", "payeeIban": "DE…", "payeeName": "Max Mustermann", "payeeOrganisationId": null }

201 { "vopReference": "VOP-01JAB…", "result": "MATCH", "actualName": null,
      "expiresAt": "…", "respondedBy": "PEERDEFFXXX" }
```

`result` ∈ `MATCH`, `CLOSE_MATCH` (then `actualName` is set), `NO_MATCH`, `NOT_POSSIBLE`. Always `201` once the
request is valid — a responder failure is a `NOT_POSSIBLE` result, not an error (UC-02 3a).
`422 PAYEE_BANK_UNKNOWN` if the IBAN's bank code is not in the directory. `400 VALIDATION_FAILED` with `errors`:
`debtorIban`, `payeeIban` (`REQUIRED`, `INVALID_IBAN`), `payeeName` (`REQUIRED`, `TOO_LONG` over 70, the column),
`payeeOrganisationId` (`TOO_LONG` over 35; blank is none). `vopReference` is `VOP-` + a ULID. Request and response
records are `com.tpe.contracts.vop` ([01 §4](01-shared-libraries.md#4-contracts)); no authentication until M5
([ADR-012](../architecture/decisions/ADR-012-service-and-staff-authentication.md)), `@InternalApi`. No
`Idempotency-Key` (an exception to 00 §7): the call is read-like, every call issues a new reference, and a retry only
leaves an unused one behind ([10 §3.4](10-payment-api.md)).

**`POST /v1/verifications/{vopReference}/check`** (UC-01 4)

```json
{ "debtorIban": "DE…", "payeeIban": "DE…", "payeeName": "Max Mustermann" }
200 { "valid": true,  "result": "CLOSE_MATCH" }
200 { "valid": false, "problem": "EXPIRED" }        // or MISMATCH, UNKNOWN
```

Comparison is exact on the normalised IBANs and on the payee name after trimming and collapsing whitespace
(case-sensitive otherwise: the payer must pay to exactly the name that was verified). An expired reference is
`EXPIRED` whatever the values; a reference retention deleted is `UNKNOWN`. Same `400` rules as above.

### 3.2 Scheme endpoint (caller: counterparty PSPs, simulated)

**`POST /vop/v1/verifications`** (UC-03) — simplified EPC VoP API.

```json
Request:  { "requestId": "…", "requestingPspBic": "PEERDEFFXXX",
            "iban": "DE…", "name": "Anna Schmidt", "organisationId": null }
Response: { "requestId": "…", "result": "CLOSE_MATCH", "actualName": "Anna Schmidt-Berg" }
```

Authentication: in the MVP the requesting PSP is identified by `requestingPspBic`, which must be in the bank
directory (`401 UNKNOWN_PARTICIPANT` otherwise); mTLS in production (UC-03 precondition), where the PSP is taken from
the client certificate and a differing `requestingPspBic` is refused with `401 UNKNOWN_PARTICIPANT`. Requests above
the per-PSP limit get `429 RATE_LIMITED` ([ADR-018](../architecture/decisions/ADR-018-rate-limiting.md)).

Authentication comes first for any body that parses as JSON (one that does not is `400 VALIDATION_FAILED`): a request
whose `requestingPspBic` is missing, not a BIC or not a participant of the directory (compared by institution,
`BankDirectory.lookup(Bic)`: `PEERDEFF` is `PEERDEFFXXX`) is `401` whatever its other fields hold, and is not audited.
Then `400 VALIDATION_FAILED` with `errors`: `requestId` (`REQUIRED`, `TOO_LONG` over 64), `iban` (`REQUIRED`,
`INVALID_IBAN`; paper format accepted), `name` (`REQUIRED`, `TOO_LONG` over 140), `organisationId` (`TOO_LONG` over
35; blank is none). `actualName` is `null` unless the result is `CLOSE_MATCH`.

## 4. Messaging

Consumes `accounts.events` ([02 §5](02-messaging-contracts.md#5-accountsevents-ledger--vop-risk)), group `vop-service`:
upsert `account_holders` by `accountId` when `revision` is newer. Produces nothing. A `null` value (tombstone, [ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md)) deletes the row.

## 5. Data model

```sql
CREATE TABLE account_holders (
  account_id   UUID PRIMARY KEY,
  iban         VARCHAR(34) NOT NULL UNIQUE,
  holder_type  VARCHAR(12) NOT NULL CHECK (holder_type IN ('PERSON', 'ORGANISATION', 'UNKNOWN')),
  holder_name  VARCHAR(70) NOT NULL,
  normalised_name VARCHAR(140) NOT NULL,
  organisation_id VARCHAR(35),
  status       VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'BLOCKED', 'CLOSED', 'UNKNOWN')),
  revision     BIGINT NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE vop_results (
  vop_reference VARCHAR(40) PRIMARY KEY,
  debtor_iban   VARCHAR(34) NOT NULL,
  payee_iban    VARCHAR(34) NOT NULL,
  payee_name    VARCHAR(70),                -- nulled by retention
  result        VARCHAR(12) NOT NULL,
  actual_name   VARCHAR(70),                -- nulled by retention
  responded_by  VARCHAR(11) NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX vop_results_named_expiry ON vop_results (expires_at)   -- retention: names to clear
  WHERE payee_name IS NOT NULL OR actual_name IS NOT NULL;
CREATE INDEX vop_results_created ON vop_results (created_at);       -- retention: rows to delete

CREATE TABLE responder_audit (
  id BIGSERIAL PRIMARY KEY, request_id VARCHAR(64) NOT NULL, requesting_psp_bic VARCHAR(11) NOT NULL,
  iban_hash CHAR(64) NOT NULL, name_hash CHAR(64) NOT NULL,    -- HMAC-SHA-256 keyed with a secret pepper, hex
  pepper_version SMALLINT NOT NULL,                             -- which pepper hashed this row (ADR-017)
  result VARCHAR(12) NOT NULL, at TIMESTAMPTZ NOT NULL
);
```

Plus `inbox`.

## 6. Behaviour

### 6.1 Requesting (UC-02)

1. Look up the payee bank in the directory (`422` if unknown).
2. Own bank → call the responding service locally (no HTTP), `respondedBy = ownBic`.
3. Other bank → `POST <vopEndpoint>` with timeout `responder-timeout` (5 s); timeout, connect error, `5xx` or invalid
   response → `NOT_POSSIBLE` (UC-02 3a). No retry (the time budget is the timeout). Behind the port
   `application.requesting.CounterpartyVop`; until the VoP remote track adds the client, its adapter answers
   `NOT_POSSIBLE` with `cause=LOCAL`.
4. Store `vop_results` with `expires_at = now + reference-validity` (30 min, UC-02 postcondition); return.

### 6.2 Responding (UC-03) and name matching

1. Look up `account_holders` by IBAN. Unknown or `CLOSED` → `NOT_POSSIBLE` (UC-03 2a). `BLOCKED` accounts are
   answered normally (blocking is not disclosed).
2. If the request has `organisationId` and the holder is an organisation with an `organisation_id`: equal →
   `MATCH`, different → `NO_MATCH` (UC-03 3a).
3. Otherwise score names:
   - **Normalise:** Unicode NFKD, strip diacritics, lower-case, `ß → ss`, remove punctuation, collapse whitespace;
     drop legal-form tokens from a list (`gmbh`, `ag`, `kg`, `ohg`, `sa`, `sarl`, `bv`, `nv`, `ltd`, `llc`, `spa`,
     `srl`, `ug`, `e.k.`, …; configurable); drop titles (`dr`, `prof`, `herr`, `frau`, `mr`, `mrs`).
   - **Score:** tokens sorted alphabetically, Jaro-Winkler similarity on the joined strings; also compare the
     unsorted strings and take the max (handles word order).
   - **Map:** score ≥ `match-threshold` (0.95) → `MATCH`; ≥ `close-match-threshold` (0.85) → `CLOSE_MATCH` with
     `actualName` = holder name as stored; otherwise `NO_MATCH`.
4. Write `responder_audit` (hashed values only: the IBAN in electronic format, the name as requested) and return.
   p99 ≤ 1 s; lookups never call ledger-service (UC-03 NFR).

The matcher is a pure function in `domain.matching` with a table-driven test file of ≥ 50 name pairs
(`src/test/resources/name-matching-cases.csv`, columns: holder, input, expected). Jaro-Winkler weighs a shared prefix,
so one wrong letter late in a longer name can still reach `match-threshold` (`Anna Schmidt` / `Anna Schmitt`: 0.967 →
`MATCH`); the typo row of VOP-AC-04 is `Anna Schmidt-Berg` / `Anna Schmitt` (0.913 → `CLOSE_MATCH`). Steps 1–3 are
the pure `HolderVerifier`; organisation ids compare trimmed, ignoring case.

### 6.3 Retention

Hourly job (`PollingWorker`): for `vop_results` with `expires_at < now − result-retention` (24 h) set `payee_name`
and `actual_name` to `NULL`; delete rows older than 30 days. An expired reference stays checkable as `EXPIRED`
until it is deleted. `responder_audit` kept 13 months (hashed only). See
[Q-09](40-open-questions.md).

## 7. Configuration

| Property | Default |
|---|---|
| `tpe.vop.responder-timeout` | `5s` |
| `tpe.vop.reference-validity` | `30m` |
| `tpe.vop.result-retention` | `24h` |
| `tpe.vop.retention.poll-interval` | `1h` |
| `tpe.vop.match-threshold` / `close-match-threshold` | `0.95` / `0.85` |
| `tpe.vop.legal-forms` | list above |
| `tpe.vop.titles` | list above |
| `tpe.vop.audit-peppers` | version → pepper, secret ([ADR-017](../architecture/decisions/ADR-017-secrets-keys-and-encryption-at-rest.md)), required |
| `tpe.vop.active-pepper-version` | required; new `responder_audit` rows use it |
| `tpe.vop.responder.rate-limit.per-psp` | `50/s` per instance ([ADR-018](../architecture/decisions/ADR-018-rate-limiting.md)) |

## 8. Non-functional requirements

- Reference check p99 ≤ 50 ms: it runs inside the 300 ms initiation budget of payment-api.
- Verification ≤ 5 s by construction (responder timeout); responder p99 ≤ 1 s at 200 requests/s (UC-03).
- Tier 0: payment-api needs a valid reference for every payment, so a vop-service outage stops initiation.
- Metrics: `tpe.vop.requests{role,route,result,cause}` (timer; `REQUESTER` with route `OWN` / `REMOTE`, `RESPONDER`
  for the scheme endpoint; `cause` is `NONE` for every answer a responder gave, `LOCAL` / `EXTERNAL` for a
  `NOT_POSSIBLE` of ours / the counterparty's), `tpe.vop.retention.overdue` (rows still named 2 h after their
  retention passed).
- Service levels: SLO-VOP-01 … -06 in [25 §5.4](25-service-levels.md#54-vop-service); SLA-03, SLA-07 rest on them.

## 9. Acceptance criteria

- **VOP-AC-01** Verification of an own-customer IBAN with the exact holder name → `MATCH` without any HTTP call. (UC-02 3)
- **VOP-AC-02** Counterparty responder slower than 5 s → `NOT_POSSIBLE` within 5.2 s, and a reference is issued. (UC-02 3a)
- **VOP-AC-03** Reference check fails `MISMATCH` if debtor IBAN, payee IBAN or payee name differ from the stored
  values; `EXPIRED` after 30 min; `UNKNOWN` for a made-up reference. (UC-01 4a, UC-02 7)
- **VOP-AC-04** Name-matching table passes: diacritics (`Müller`/`Mueller`/`Muller`), word order
  (`Schmidt Anna`), legal forms (`ACME GmbH`/`ACME`), typos (`Anna Schmitt` → `CLOSE_MATCH`), clearly different
  names → `NO_MATCH`. (UC-03 3–4)
- **VOP-AC-05** Unknown or closed IBAN → `NOT_POSSIBLE`; blocked account → normal result. (UC-03 2a)
- **VOP-AC-06** Organisation-id match takes precedence over the name. (UC-03 3a)
- **VOP-AC-07** Projection applies `AccountOpened` then ignores a replayed older revision.
- **VOP-AC-08** After retention, stored results contain no names; audit rows never contain clear-text names or IBANs.
- **VOP-AC-09** Responder p99 ≤ 1 s at 200 requests/s.
