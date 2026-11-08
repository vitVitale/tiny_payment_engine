-- Pending credits: memos of incoming payments accepted but not yet settled (specs/12-ledger-service.md §5, ADR-005),
-- part of neither balance; and their tombstones (ADR-009): a cancel of an unknown id inserts the row first, so a later
-- create of that id is refused. The id is the incoming paymentId (ADR-010); debtor_agent_bic and tx_id are reference
-- columns. The table is new and empty, so its indexes need no CONCURRENTLY (00 §9).
CREATE TABLE pending_credits (
  pending_credit_id UUID PRIMARY KEY,                 -- incoming paymentId (ADR-010)
  debtor_agent_bic  VARCHAR(11),
  tx_id             VARCHAR(35),
  account_id        UUID REFERENCES accounts,         -- NULL for tombstones
  amount_minor      BIGINT CHECK (amount_minor > 0),  -- NULL for tombstones
  status            VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE','CONFIRMED','CANCELLED')),
  payment_id        UUID,
  entry_id          UUID REFERENCES journal_entries,
  created_at        TIMESTAMPTZ NOT NULL,
  finalised_at      TIMESTAMPTZ,
  tombstone         BOOLEAN NOT NULL DEFAULT false,
  -- a tombstone is a cancelled credit with nothing pending; every other one has an account and an amount
  CHECK (tombstone = (account_id IS NULL)),
  CHECK ((account_id IS NULL) = (amount_minor IS NULL)),
  CHECK (NOT tombstone OR status = 'CANCELLED')
);
CREATE INDEX pending_credits_active_account ON pending_credits (account_id) WHERE status = 'ACTIVE';
-- the hourly retention delete of tombstones (12 §6.1, ADR-009)
CREATE INDEX pending_credits_tombstones ON pending_credits (created_at) WHERE tombstone;
