-- Accounts and the audit log of admin actions (specs/12-ledger-service.md §5). The table is new and empty, so its
-- indexes need no CONCURRENTLY (00 §9).
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

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY, actor VARCHAR(100) NOT NULL, action VARCHAR(50) NOT NULL,
  account_id UUID, details JSONB NOT NULL, at TIMESTAMPTZ NOT NULL
);
