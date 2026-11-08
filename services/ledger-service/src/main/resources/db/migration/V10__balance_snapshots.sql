-- Technical-account balances (specs/12-ledger-service.md §5, §6.3, ADR-005): postings never write a technical account's
-- row, so its balance is the latest snapshot plus the postings after up_to_posting_id. The key makes a snapshot of the
-- same boundary unique, so two instances never write it twice. The table is new and empty (00 §9).
CREATE TABLE balance_snapshots (          -- technical accounts only
  account_id       UUID NOT NULL REFERENCES accounts,
  up_to_posting_id BIGINT NOT NULL,
  balance_minor    BIGINT NOT NULL,         -- Σ CR − Σ DR up to and including up_to_posting_id
  taken_at         TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (account_id, up_to_posting_id)
);
