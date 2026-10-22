# 14 risk-service

Architecture: [C4 components](../architecture/c4/03-components-risk-service.puml),
[UC-04](../architecture/use-cases/UC-04-execute-outgoing-payment.md) 2, [UC-06](../architecture/use-cases/UC-06-receive-incoming-payment.md) 3,
[UC-09](../architecture/use-cases/UC-09-daily-sanctions-screening.md).

## 1. Responsibilities

- Per-payment fraud assessment for outgoing and incoming payments: `ALLOW` / `DENY` / `REVIEW`, in-memory rules,
  no remote calls on the assessment path.
- Per-payment screening against **non-EU** sanctions lists only (the only source of per-payment `RR04`).
- Daily (and on list update) screening of all customers against the EU list; review cases; provisional and
  confirmed account blocks via ledger-service (Regulation (EU) 2024/886).

Not responsible for: account status checks during payments (ledger does them in the same transaction), the
instant-payment decision for `REVIEW` (orchestrator treats it as `DENY`).

## 2. Module layout

| Package | C4 component |
|---|---|
| `api.assessment` | Assessment API |
| `api.cases` | Screening case API |
| `api.accounts` | Account events listener |
| `domain.rules` | Fraud rule engine |
| `domain.sanctions` | Sanctions matcher |
| `application.assessment` | assessment service (rules + persistence of assessments) |
| `application.lists` | Sanctions list importer |
| `application.screening` | Customer screening job |
| `application.cases` | Case manager |
| `infrastructure.ledger` | Ledger client |
| `infrastructure.provider` | Provider client |
| `infrastructure.persistence` | Repositories |

## 3. REST API

### 3.1 `POST /v1/risk/assessments` (internal, caller: payment-orchestrator)

```json
{
  "assessmentId": "0192f0e4-…:OUTGOING",
  "paymentId": "0192f0e4-…",
  "direction": "OUTGOING",
  "txId": "…",
  "amountMinor": 12345, "currency": "EUR",
  "debtor":   { "name": "…", "iban": "…" },
  "creditor": { "name": "…", "iban": "…" },
  "debtorAgentBic": "…", "creditorAgentBic": "…",
  "remittanceInformation": "…",
  "vopResult": "CLOSE_MATCH"
}

200 { "assessmentId": "…", "decision": "DENY", "reasonCode": "FR01",
      "ruleHits": [ { "ruleId": "VELOCITY_COUNT_1H", "score": 1.0 } ], "assessedAt": "…" }
```

- `assessmentId` = `<paymentId>:<direction>` (on-us payments are assessed twice: `OUTGOING` for the payer,
  `INCOMING` for the payee). Idempotent: same id → stored decision returned.
- `reasonCode` is `FR01` for fraud rules, `RR04` for a non-EU sanctions hit, absent for `ALLOW`.
- `vopResult` is optional (outgoing only; orchestrator does not have it in the MVP → may be `null`).
- p99 ≤ 50 ms service-side. No outbound I/O except the local DB.

### 3.2 Screening case API (role `COMPLIANCE_OFFICER`)

| Method & path | Purpose |
|---|---|
| `GET /v1/screening-cases?status=OPEN` | List cases |
| `GET /v1/screening-cases/{caseId}` | Case with holder data, list entry, score, run |
| `POST /v1/screening-cases/{caseId}/confirm` | True hit → `SANCTIONS` block on all customer accounts (UC-09 5–6) |
| `POST /v1/screening-cases/{caseId}/dismiss` | False positive → lift provisional block, remember decision (UC-09 5a) |
| `GET /v1/screening-runs?limit=` | Run history (list version, counts, duration) |
| `POST /v1/screening-runs` | Start a run manually (on the current list version) |
| `GET /v1/sanctions-lists` | Imported versions |

Body for confirm / dismiss: `{ "comment": "…" }`. Actor = JWT `sub`.

## 4. Messaging

Consumes `accounts.events` (group `risk-service`): upsert `account_holders` by `accountId` if `revision` newer. A `null` value (tombstone, [ADR-007](../architecture/decisions/ADR-007-account-events-as-compacted-snapshots.md)) deletes the row.
`AccountOpened` for a new holder also triggers a single-customer screening against the current list (so a listed
person cannot open an account and transact before the next daily run).

## 5. Data model

```sql
CREATE TABLE account_holders (
  account_id UUID PRIMARY KEY, customer_id VARCHAR(50) NOT NULL, iban VARCHAR(34) NOT NULL UNIQUE,
  holder_type VARCHAR(12) NOT NULL, holder_name VARCHAR(70) NOT NULL, date_of_birth DATE,
  country CHAR(2), organisation_id VARCHAR(35), status VARCHAR(10) NOT NULL,
  opened_at TIMESTAMPTZ NOT NULL, revision BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX account_holders_customer ON account_holders (customer_id);

CREATE TABLE assessments (
  assessment_id VARCHAR(60) PRIMARY KEY, payment_id UUID NOT NULL, direction VARCHAR(8) NOT NULL,
  debtor_iban VARCHAR(34) NOT NULL, creditor_iban VARCHAR(34) NOT NULL, amount_minor BIGINT NOT NULL,
  decision VARCHAR(6) NOT NULL, reason_code VARCHAR(4), rule_hits JSONB NOT NULL, assessed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX assessments_debtor_time ON assessments (debtor_iban, assessed_at);
CREATE INDEX assessments_creditor_time ON assessments (creditor_iban, assessed_at);

CREATE TABLE rules (                         -- parameters only; logic is code
  rule_id VARCHAR(40) PRIMARY KEY, enabled BOOLEAN NOT NULL, direction VARCHAR(8) NOT NULL,
  parameters JSONB NOT NULL, action VARCHAR(6) NOT NULL CHECK (action IN ('DENY','REVIEW')),
  updated_at TIMESTAMPTZ NOT NULL, updated_by VARCHAR(100) NOT NULL
);

CREATE TABLE sanctions_list_versions (
  version_id UUID PRIMARY KEY, list_code VARCHAR(20) NOT NULL,   -- EU, UN, …
  source_version VARCHAR(100) NOT NULL, content_hash CHAR(64) NOT NULL, entry_count INT NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL, current BOOLEAN NOT NULL,
  UNIQUE (list_code, content_hash)
);
CREATE UNIQUE INDEX one_current_list ON sanctions_list_versions (list_code) WHERE current;

CREATE TABLE sanctions_entries (
  version_id UUID NOT NULL REFERENCES sanctions_list_versions, entry_id VARCHAR(50) NOT NULL,
  entry_type VARCHAR(12) NOT NULL, names JSONB NOT NULL, dates_of_birth JSONB, countries JSONB, identifiers JSONB,
  entry_hash CHAR(64) NOT NULL,
  PRIMARY KEY (version_id, entry_id)
);

CREATE TABLE screening_runs (
  run_id UUID PRIMARY KEY, version_id UUID NOT NULL, trigger VARCHAR(20) NOT NULL,  -- DAILY, LIST_UPDATE, MANUAL, NEW_CUSTOMER
  started_at TIMESTAMPTZ NOT NULL, finished_at TIMESTAMPTZ, customers_screened INT, hits INT,
  provisional_blocks INT, status VARCHAR(10) NOT NULL
);

CREATE TABLE screening_cases (
  case_id UUID PRIMARY KEY, run_id UUID NOT NULL, customer_id VARCHAR(50) NOT NULL,
  version_id UUID NOT NULL, entry_id VARCHAR(50) NOT NULL, score NUMERIC(4,3) NOT NULL,
  provisional_block BOOLEAN NOT NULL, status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN','CONFIRMED','DISMISSED')),
  decision_key CHAR(64) NOT NULL, decided_by VARCHAR(100), decided_at TIMESTAMPTZ, comment VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX one_open_case ON screening_cases (customer_id, entry_id) WHERE status = 'OPEN';

CREATE TABLE dismissed_decisions (
  decision_key CHAR(64) PRIMARY KEY,         -- SHA-256(entry_id, entry_hash, holder data hash)
  case_id UUID NOT NULL, dismissed_at TIMESTAMPTZ NOT NULL
);
```

Plus `inbox`.

## 6. Behaviour

### 6.1 Fraud rules (MVP rule set)

Rules are Java classes implementing `Rule { RuleResult evaluate(AssessmentRequest, RiskContext) }`; parameters come
from the `rules` table, cached in memory and refreshed every 30 s. `RiskContext` provides velocity aggregates
read from `assessments` (indexed range query, single round trip) and the holder projection.

| Rule | Direction | Default parameters | Action |
|---|---|---|---|
| `AMOUNT_LIMIT` | both | > 50 000.00 EUR | DENY |
| `VELOCITY_COUNT_1H` | OUTGOING | > 20 payments per debtor IBAN in 1 h | DENY |
| `VELOCITY_AMOUNT_24H` | OUTGOING | > 100 000.00 EUR per debtor IBAN in 24 h | DENY |
| `NEW_PAYEE_HIGH_AMOUNT` | OUTGOING | first payment to this creditor IBAN and amount > 5 000.00 EUR and `vopResult` ≠ `MATCH` | REVIEW |
| `MULE_NEW_ACCOUNT` | INCOMING | creditor account opened < 7 days ago and > 10 incoming payments in 24 h | DENY |
| `NON_EU_SANCTIONS` | both | counterparty name matched against current non-EU lists (UN) with score ≥ 0.97 | DENY, `RR04` |

Decision: any `DENY` hit → `DENY` (reason `RR04` if a sanctions rule hit, else `FR01`); else any `REVIEW` → `REVIEW`
(`FR01`); else `ALLOW`. Only `ALLOW` assessments count towards velocity aggregates.

EU-list screening is **never** done per payment (UC-09 NFR).

### 6.2 Sanctions list import (UC-09 1, 1a)

- Daily at `import-cron` (02:00 UTC) and every `poll-interval` (15 min) the importer fetches the provider's list
  metadata; if `content_hash` changed, downloads, parses (adapter per format; MVP format = simulator JSON, see
  [17 §3.4](17-csm-simulator.md#34-sanctions-list-provider)), stores a new version and marks it current
  (one transaction), then starts a `LIST_UPDATE` run immediately.
- A daily `DAILY` run starts at `daily-run-cron` (03:00 UTC) on the current version even if nothing changed.
- Provider unavailable → keep the current version, `tpe.sanctions.import.failures` metric + alert, retry hourly.

### 6.3 Customer screening (UC-09 2–4)

- Streams `account_holders` grouped by `customer_id` (only customers with at least one non-closed account) in pages
  of 1 000; matches each against all entries of the current EU version; parallel workers (virtual threads).
- **Matcher:** normalisation as in vop-service (shared logic is *not* shared code — copy the rules, they may
  diverge), plus transliteration of Cyrillic / Greek to Latin; name score = best Jaro-Winkler over all name
  variants of the entry (token-sorted); +0.02 if date of birth matches, −0.10 if date of birth present on both and
  differs; identifiers (organisation id) equal → score 1.0.
- `score ≥ review-threshold` (0.85) → case (unless `decision_key` is in `dismissed_decisions`, UC-09 5a remembered).
- `score ≥ freeze-threshold` (0.97) **and** (dob matches or no dob on the entry) → provisional block: for each
  account of the customer `POST /v1/accounts/{id}/block` reason `SANCTIONS_PROVISIONAL` (UC-09 4). Blocks are issued
  as soon as the hit is found, not at the end of the run.
- Run statistics in `screening_runs`. A full run over 1 M customers must finish within 1 h (UC-09 NFR) → target
  ≥ 300 customers / s with 10 000 list entries; use a blocking index (first 3 chars of each normalised token) to
  avoid comparing every customer with every entry.

### 6.4 Case decisions

- **Confirm:** for each account of the customer (`GET /v1/customers/{customerId}/accounts` on ledger) block with
  `SANCTIONS` (ledger replaces any provisional block). Case `CONFIRMED`.
- **Dismiss:** lift `SANCTIONS_PROVISIONAL` on each account if the case had applied it; store `decision_key` in
  `dismissed_decisions`. Case `DISMISSED`.
- Ledger calls are idempotent (`Idempotency-Key: sanctions-<caseId>-<accountId>-<action>`) and retried with
  back-off until success; the case status changes only after all calls succeeded.

## 7. Configuration

| Property | Default |
|---|---|
| `tpe.risk.sanctions.provider-url` | `http://csm-simulator:8090/sanctions/v1/lists` |
| `tpe.risk.sanctions.import-cron` / `poll-interval` / `daily-run-cron` | `0 0 2 * * *` / `15m` / `0 0 3 * * *` |
| `tpe.risk.sanctions.review-threshold` / `freeze-threshold` | `0.85` / `0.97` |
| `tpe.risk.rules.refresh-interval` | `30s` |
| `tpe.clients.ledger.base-url` | `http://ledger-service:8082` |

## 8. Non-functional requirements

- Assessment p99 ≤ 50 ms service-side (§3.1). Every failed assessment rejects a payment (the orchestrator fails closed).
- Full screening run over 1 M customers ≤ 1 h (UC-09); provisional blocks applied as hits are found (§6.3).
- Metrics: `tpe.risk.assessment.duration{direction,decision}`, `tpe.risk.rules.age`, `tpe.sanctions.list.age`,
  `tpe.sanctions.run.duration{type}`, `tpe.sanctions.run.last_success{type}`, `tpe.sanctions.freeze.latency`,
  `tpe.sanctions.import.failures`.
- Service levels: SLO-RSK-01 … -08 in [25 §5.5](25-service-levels.md#55-risk-service); the regulatory SLA-08 rests on them.

## 9. Acceptance criteria

- **RSK-AC-01** Each rule in §6.1 has an allow and a deny example; decision aggregation follows the stated order.
- **RSK-AC-02** Same `assessmentId` twice → same stored decision, one row.
- **RSK-AC-03** A non-EU list hit on the creditor name → `DENY RR04`; an EU list entry with the same name → `ALLOW`
  (no per-payment EU screening). (UC-04 2a, UC-09 NFR)
- **RSK-AC-04** Assessment p99 ≤ 50 ms with 1 M rows in `assessments`.
- **RSK-AC-05** New list version → `LIST_UPDATE` run starts within 1 min of import; high-confidence hit blocks all
  accounts of the customer provisionally within minutes, before any review. (UC-09 4, NFR)
- **RSK-AC-06** Confirm → `SANCTIONS` block on all accounts; subsequent outgoing and incoming payments for them fail
  `AC06` (system test). (UC-09 6–7)
- **RSK-AC-07** Dismiss → provisional block lifted; the next run with the unchanged list entry and holder data creates
  no new case. (UC-09 5a)
- **RSK-AC-08** Provider down → current version kept, alert metric incremented, retry hourly. (UC-09 1a)
- **RSK-AC-09** A newly opened account of a listed person is screened on `AccountOpened` and blocked provisionally.
- **RSK-AC-10** Full run over 100 000 synthetic customers × 5 000 entries completes in ≤ 6 min on CI hardware
  (extrapolates to the 1 h / 1 M target).
