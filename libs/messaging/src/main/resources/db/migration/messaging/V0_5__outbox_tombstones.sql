-- Tombstones on compacted topics (specs/01-shared-libraries.md §3.2, ADR-007): an outbox row with no type, version
-- or payload is published as a null value. The previous release's relay already sends the payload as it is, so it
-- publishes such rows correctly (00 §9). Dropping NOT NULL is metadata only; the CHECK is added NOT VALID, so it checks
-- new rows only (every existing row has all three) and a later release may VALIDATE it. The ALTER still needs a brief
-- exclusive lock on the busy outbox: a lock_timeout makes it fail fast, for the deploy to retry, rather than queue
-- behind the relay and stall every writer.
SET LOCAL lock_timeout = '5s';

ALTER TABLE outbox
  ALTER COLUMN message_type DROP NOT NULL,
  ALTER COLUMN schema_version DROP NOT NULL,
  ALTER COLUMN payload DROP NOT NULL,
  ADD CONSTRAINT outbox_tombstone_shape
    CHECK ((message_type IS NULL) = (payload IS NULL) AND (schema_version IS NULL) = (payload IS NULL)) NOT VALID;
