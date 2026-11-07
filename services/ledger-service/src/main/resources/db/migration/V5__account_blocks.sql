-- Blocks per reason (specs/12-ledger-service.md §3.2, §5): an account is BLOCKED while at least one is active. Lifted
-- blocks stay as history. The table is new and empty, so its indexes need no CONCURRENTLY (00 §9).
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
