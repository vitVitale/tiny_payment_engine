-- flyway:executeInTransaction=false
-- Retention deletes inbox rows by age (specs/01-shared-libraries.md §3.1); without it each chunk scans the table.
CREATE INDEX CONCURRENTLY IF NOT EXISTS inbox_processed ON inbox (processed_at);
