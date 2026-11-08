-- flyway:executeInTransaction=false
-- The closed accounts whose accounts.events tombstone is still due (12 §4): the job's scan. accounts is live, so the
-- index is built CONCURRENTLY, in its own migration (00 §9).
CREATE INDEX CONCURRENTLY IF NOT EXISTS accounts_events_tombstone_due ON accounts (closed_at)
  WHERE status = 'CLOSED' AND events_tombstoned_at IS NULL;
