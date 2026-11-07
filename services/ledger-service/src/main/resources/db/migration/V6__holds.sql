-- Holds: memos reserving money on a payer account (specs/12-ledger-service.md §5, ADR-005), and their tombstones
-- (ADR-009): a release of an unknown id inserts the row first, so a later create of that id is refused. The id is the
-- primary key, so a create and a release racing for the same id are decided by whichever insert commits first. The
-- table is new and empty, so its indexes need no CONCURRENTLY (00 §9).
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
  tombstone    BOOLEAN NOT NULL DEFAULT false,
  -- a tombstone is a released hold with nothing reserved; every other hold has an account and an amount
  CHECK (tombstone = (account_id IS NULL)),
  CHECK ((account_id IS NULL) = (amount_minor IS NULL)),
  CHECK (NOT tombstone OR status = 'RELEASED')
);
CREATE INDEX holds_active_account ON holds (account_id) WHERE status = 'ACTIVE';
-- the hourly retention delete of tombstones (12 §6.1, ADR-009)
CREATE INDEX holds_tombstones ON holds (created_at) WHERE tombstone;
