-- flyway:executeInTransaction=false
-- Retention deletes published rows by age (specs/01-shared-libraries.md §3.1); without it each chunk scans the table.
CREATE INDEX CONCURRENTLY IF NOT EXISTS outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
