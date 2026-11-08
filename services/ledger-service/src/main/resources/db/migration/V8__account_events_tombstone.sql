-- When the closed account's key was deleted from accounts.events (specs/12-ledger-service.md §4, ADR-007): the daily
-- job publishes one tombstone per account, 13 months after closed_at, and records it here. Nullable, so the running
-- release is unaffected (00 §9).
ALTER TABLE accounts ADD COLUMN events_tombstoned_at TIMESTAMPTZ;
