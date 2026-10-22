# 10 payment-api

Architecture: [C4 components](../architecture/c4/03-components-payment-api.puml),
[UC-01](../architecture/use-cases/UC-01-initiate-instant-credit-transfer.md), [UC-02](../architecture/use-cases/UC-02-verify-payee-outgoing.md) (proxy),
[UC-07](../architecture/use-cases/UC-07-query-payment-status.md).

## 1. Responsibilities

- Customer-facing REST API for customer channels: payment initiation (`pain.001` JSON or XML), payee
  verification, status queries (JSON or `pain.002`).
- Authentication / authorisation of the API client and debtor-account ownership.
- Request idempotency (`Idempotency-Key` → stored response) and derivation of `commandId`.
- Payload validation and VoP reference check before anything reaches the orchestrator.

Not responsible for: payment state (orchestrator), VoP matching (vop-service). It holds no payment data except
stored idempotent responses.

## 2. Module layout

| Package | C4 component |
|---|---|
| `api.security` | Security filter chain |
| `api.payment` | Payment controller |
| `api.vop` | Payee verification controller |
| `application.idempotency` | Idempotency guard |
| `application.validation` | Payload validator |
| `application.submission` | Payment submission service |
| `application.mapping` | ISO 20022 mapper (`pain.001` ↔ command, state → `pain.002`) |
| `infrastructure.orchestrator` | Orchestrator client |
| `infrastructure.vop` | VoP client |
| `infrastructure.persistence` | Idempotency repository |

## 3. REST API (external)

OpenAPI document `payment-api.yaml` is the contract for channel teams. Authentication: Bearer JWT
([00 §12](00-engineering-baseline.md#121-authentication-and-authorisation)).

### 3.1 `POST /v1/payments` — initiate (UC-01)

Scope `payments:write`. Headers: `Idempotency-Key` (required, 1–64 chars `[A-Za-z0-9_-]`), `Content-Type`
`application/json` or `application/xml`; for XML also `VoP-Reference` (no standard `pain.001` field carries it).

JSON body (simplified `pain.001`, one transaction):

```json
{
  "endToEndId": "INV-2026-0042",
  "debtor":   { "name": "Anna Schmidt", "iban": "DE89370400440532013000" },
  "creditor": { "name": "Max Mustermann", "iban": "DE02200200200000123456", "bic": "PEERDEFFXXX" },
  "amount":   { "value": "123.45", "currency": "EUR" },
  "remittanceInformation": "Invoice 42",
  "vopReference": "VOP-01JAB…"
}
```

XML body: `pain.001.001.09` with exactly one `PmtInf` and one `CdtTrfTxInf`; `PmtTpInf/LclInstrm/Cd = INST`;
`ReqdExctnDt/Dt` today or absent. Mapped to the same internal command as JSON. `endToEndId` defaults to `NOTPROVIDED`
when absent.

Response `202 Accepted`, `Location: /v1/payments/{paymentId}`:

```json
{ "paymentId": "0192f0e4-…", "status": "PDNG", "internalState": "RECEIVED", "acceptedAt": "…" }
```

Processing order (UC-01 1–8):

1. Authenticate; scope check; rate limits (`429 RATE_LIMITED`, before any database access); `debtor.iban` ∈ token
   `accounts` claim, else `403 DEBTOR_ACCOUNT_NOT_OWNED`.
2. Idempotency guard (§5.1): replay / conflict / in-progress.
3. Validate (§5.2) → `400` with field errors.
4. VoP reference check with vop-service (§5.3) → `422 VOP_REQUIRED`.
5. Derive `commandId = CommandId.derive(client_id, Idempotency-Key)`; call orchestrator `POST /v1/payments`.
6. Orchestrator `201` or `200` → store response for the key, return `202` (same body in both cases, UC-01 6a).
7. Orchestrator timeout / `5xx` / connect failure → delete the in-progress key, `503 ORCHESTRATOR_UNAVAILABLE`
   with `Retry-After: 1` (UC-01 5a).

### 3.2 `GET /v1/payments/{paymentId}` — status (UC-07)

Scope `payments:read`. Calls orchestrator `GET /v1/payments/{id}`; `404` if not found **or** `debtor.iban` not in
the token's `accounts` (no information leak, UC-07 1a). Response:

```json
{
  "paymentId": "…", "status": "RJCT", "reasonCode": "AM04", "internalState": "REJECTED",
  "endToEndId": "…", "amount": { "value": "123.45", "currency": "EUR" },
  "creditor": { "name": "…", "iban": "…" }, "acceptedAt": "…", "finalisedAt": "…"
}
```

Status mapping: `RECEIVED`, `RISK_CHECKED`, `FUNDS_RESERVED`, `SENT_TO_CSM`, `INVESTIGATING` → `PDNG`;
`SETTLED` → `ACSC`; `REJECTED` → `RJCT` + `reasonCode`. Only outgoing payments are visible through this API.

### 3.3 `GET /v1/payments/{paymentId}/pain.002`

Same authorisation; returns `application/xml` `pain.002.001.10` with `GrpSts`/`TxSts` from the mapping above,
`OrgnlEndToEndId`, `OrgnlUETR`, `StsRsnInf/Rsn/Cd` when rejected.

### 3.4 `POST /v1/payee-verifications` — VoP (UC-02)

Scope `vop:verify`. Body `{ "debtorIban", "payeeIban", "payeeName", "payeeOrganisationId"? }`. `debtorIban` must
be owned (`403`). IBAN checksum validated here (`400 INVALID_IBAN`, UC-02 1a); then forwarded to vop-service
`POST /v1/verifications`. Response from vop-service is returned unchanged:

```json
{ "vopReference": "VOP-…", "result": "CLOSE_MATCH", "actualName": "Max Mustermann GmbH", "expiresAt": "…" }
```

Not idempotent by key (read-like; every call creates a new reference). Timeout 5.5 s; vop-service itself enforces
the 5 s responder timeout and returns `NOT_POSSIBLE`, so payment-api only sees a timeout if vop-service is down →
`503 VOP_UNAVAILABLE`.

### 3.5 Error codes

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | field errors in `errors[]` (codes below) |
| 400 | `IDEMPOTENCY_KEY_MISSING` / `IDEMPOTENCY_KEY_INVALID` | header absent / malformed |
| 400 | `UNSUPPORTED_MEDIA_TYPE_VARIANT` | XML with more than one transaction, wrong `pain.001` version |
| 403 | `DEBTOR_ACCOUNT_NOT_OWNED` | UC-01 1a |
| 404 | `PAYMENT_NOT_FOUND` | UC-07 1a |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | same key currently being processed |
| 422 | `IDEMPOTENCY_KEY_REUSED` | UC-01 2b |
| 422 | `VOP_REQUIRED` | UC-01 4a (missing, unknown, expired or mismatching reference) |
| 422 | `CREDITOR_AGENT_NOT_REACHABLE` | creditor bank not in directory or not SCT Inst reachable |
| 429 | `RATE_LIMITED` | over a per-client or per-subject limit, with `Retry-After` ([00 §12.3](00-engineering-baseline.md#123-production-requirements) SEC-04, limits in [Q-33](40-open-questions.md#q-33-rate-limits)) |
| 503 | `ORCHESTRATOR_UNAVAILABLE`, `VOP_UNAVAILABLE` | with `Retry-After` |

Field error codes: `INVALID_IBAN`, `INVALID_BIC`, `INVALID_AMOUNT` (≤ 0, > 2 decimals, > 999 999 999.99),
`UNSUPPORTED_CURRENCY`, `INVALID_CHARACTERS` (outside the SEPA set), `TOO_LONG`, `REQUIRED`,
`SAME_DEBTOR_AND_CREDITOR`.

For XML clients a failed validation additionally offers a `pain.002` with `RJCT FF01` at
`GET /v1/payment-rejections/{rejectionId}/pain.002`, where `rejectionId` is returned in the problem body (stored 24 h).

## 4. Downstream calls

| Call | Endpoint | Timeout | Retries |
|---|---|---|---|
| orchestrator submit | `POST /v1/payments` | 1 s | 1 retry on connect failure only (idempotent by `commandId`) |
| orchestrator status | `GET /v1/payments/{id}` | 300 ms | 1 |
| vop reference check | `POST /v1/verifications/{vopReference}/check` | 300 ms | 1 |
| vop verify | `POST /v1/verifications` | 5.5 s | 0 |

## 5. Behaviour

### 5.1 Idempotency guard

Table:

```sql
CREATE TABLE idempotency_keys (
  client_id       VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(64)  NOT NULL,
  request_hash    CHAR(64)     NOT NULL,   -- SHA-256 of method + path + canonicalised body
  status          VARCHAR(12)  NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_status INT,
  response_body   JSONB,
  payment_id      UUID,
  created_at      TIMESTAMPTZ NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (client_id, idempotency_key)
);
CREATE INDEX idempotency_keys_expiry ON idempotency_keys (expires_at);

CREATE TABLE payment_rejections (            -- pain.002 FF01 for XML clients
  rejection_id UUID PRIMARY KEY, client_id VARCHAR(100) NOT NULL, xml TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL
);
```

- `INSERT … (IN_PROGRESS) ON CONFLICT DO NOTHING`. Inserted → proceed. Conflict → read the row:
  - `COMPLETED`, same hash → replay stored status + body (UC-01 2a). For a replayed `202` the body's `status` /
    `internalState` are refreshed from the orchestrator (UC-01 2a: "current status"); if that read fails the stored
    body is returned.
  - `COMPLETED`, different hash → `422 IDEMPOTENCY_KEY_REUSED` (UC-01 2b).
  - `IN_PROGRESS`, younger than 10 s → `409 IDEMPOTENCY_KEY_IN_PROGRESS`; older → treat as abandoned: take it over
    (update `created_at`) and proceed — safe because the orchestrator deduplicates by `commandId`.
- Only the `202` outcome is stored (`COMPLETED`). Validation failures (`400`, `403`, `422`) delete the
  `IN_PROGRESS` row: they had no side effect, and the client may fix the request and retry with the same key.
  `503` deletes the row too (UC-01 5a).
- Canonicalisation: JSON parsed and re-serialised with sorted keys; XML hashed after canonical XML (C14N) without
  comments.
- Retention `expires_at = created_at + 24h` (UC-01 NFR); hourly cleanup job.

### 5.2 Validation

- JSON: Bean Validation on request records + `payment-domain` value objects (`Iban`, `Bic`, `Money.parse`,
  `PartyName`, `RemittanceInformation`, SEPA character set).
- XML: `XsdValidator` for `pain.001.001.09`, then the same domain validation on the mapped values.
- `creditor.bic`, if given, must match the directory BIC for the creditor IBAN's bank code (`INVALID_BIC`).
- `BankDirectory.lookup(creditor.iban)` must exist and be `sctInstReachable` (`422 CREDITOR_AGENT_NOT_REACHABLE`).
- `debtor.iban ≠ creditor.iban` (`SAME_DEBTOR_AND_CREDITOR`).

### 5.3 VoP reference check

`POST /v1/verifications/{vopReference}/check` with `{ debtorIban, payeeIban, payeeName }`. vop-service answers
`200 { "valid": true, "result": "MATCH" }` or `200 { "valid": false, "problem": "EXPIRED" | "MISMATCH" | "UNKNOWN" }`.
`valid = false` → `422 VOP_REQUIRED` with `problem` in `detail`. The payer may proceed with any VoP *result*
(`NO_MATCH`, `NOT_POSSIBLE` included, UC-02 6a); only a valid, matching *reference* is required.

## 6. Configuration

| Property | Default |
|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | mock issuer in `local` |
| `tpe.api.idempotency.retention` | `24h` |
| `tpe.api.idempotency.in-progress-takeover` | `10s` |
| `tpe.clients.orchestrator.base-url`, `.timeout` | `http://payment-orchestrator:8081`, `1s` |
| `tpe.clients.vop.base-url` | `http://vop-service:8083` (`https://` in `prod`, [ADR-016](../architecture/decisions/ADR-016-tls-inside-the-cluster.md)) |
| `tpe.api.rate-limit.clients.<client_id>` | per channel, set at onboarding ([ADR-018](../architecture/decisions/ADR-018-rate-limiting.md)) |
| `tpe.api.rate-limit.payments-per-subject` | `30/1m` |
| `tpe.api.rate-limit.verifications-per-subject` | `20/10m`, `100/1d` |
| `tpe.api.rate-limit.instances` | instance count the per-subject limits are divided by |

## 7. Non-functional requirements

- `POST /v1/payments` p99 ≤ 300 ms (UC-01); `GET` p99 ≤ 200 ms (UC-07).
- Stateless apart from the idempotency DB; horizontally scalable.
- Metrics: `tpe.api.requests{endpoint,status}`, `tpe.api.idempotency{outcome=new|replay|reused|in_progress}`.
- Request bodies are never logged.
- Service levels: SLO-API-01 … -05 in [25 §5.1](25-service-levels.md#51-payment-api); the customer-channel SLAs
  SLA-01 … -03 in [25 §4](25-service-levels.md#4-slas) rest on them.

## 8. Acceptance criteria

- **API-AC-01** Valid JSON `pain.001` with a valid VoP reference → `202`, `Location` header, one `SubmitPayment`
  with the derived `commandId`. (UC-01)
- **API-AC-02** Valid XML `pain.001.001.09` produces the same `SubmitPayment` as the equivalent JSON.
- **API-AC-03** Repeat with same key and body → same `paymentId`, no second orchestrator payment; status reflects
  the current state. (UC-01 2a)
- **API-AC-04** Same key, different body → `422 IDEMPOTENCY_KEY_REUSED`. (UC-01 2b)
- **API-AC-05** Debtor IBAN not in the token → `403`; status query for a foreign payment → `404`. (UC-01 1a, UC-07 1a)
- **API-AC-06** Each field rule of §5.2 has a failing example returning `400` with the listed field code. For an
  XML request the problem body carries a `rejectionId` whose `pain.002` is `RJCT FF01` and validates against the
  XSD. `POST /v1/payee-verifications` with an invalid payee IBAN returns `400 INVALID_IBAN` without calling
  vop-service. (UC-01 3a, UC-02 1a)
- **API-AC-07** Expired / mismatching / unknown VoP reference → `422 VOP_REQUIRED`. (UC-01 4a)
- **API-AC-08** Orchestrator down → `503` with `Retry-After`; retry with the same key after recovery → `202`.
  Orchestrator reached but response lost (WireMock delay > timeout) → retry yields the same `paymentId`. (UC-01 5a, 6a)
- **API-AC-09** Status mapping table of §3.2 verified for every internal state. (UC-07 3)
- **API-AC-10** Two concurrent requests with the same new key → one `202`, the other `409 IDEMPOTENCY_KEY_IN_PROGRESS`
  or the replayed `202`; never two payments.
- **API-AC-11** XML payload with a DOCTYPE → `400` without entity resolution.
