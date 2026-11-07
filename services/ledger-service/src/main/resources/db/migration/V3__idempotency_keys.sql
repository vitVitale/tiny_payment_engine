-- Stored responses of mutating requests (specs/12-ledger-service.md §5, §6.1). The created_at index is for the hourly
-- retention delete; the table is new and empty, so it needs no CONCURRENTLY (00 §9).
CREATE TABLE idempotency_keys (
  idempotency_key VARCHAR(200) PRIMARY KEY,
  request_hash    CHAR(64) NOT NULL,     -- SHA-256 of canonical JSON body + path
  response_status INT NOT NULL,
  response_body   JSONB NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idempotency_keys_created ON idempotency_keys (created_at);
