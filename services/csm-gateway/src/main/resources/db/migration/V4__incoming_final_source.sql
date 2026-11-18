-- specs/15-csm-gateway.md §5, §6.4, §6.6: the source of an incoming transfer's recorded final status, so a status
-- request re-publishes its final event as it was.
-- NOT VALID: no scan of the existing rows under the lock (00 §9); new and updated rows are checked
ALTER TABLE incoming_transfers ADD COLUMN final_source VARCHAR(13);
ALTER TABLE incoming_transfers ADD CONSTRAINT incoming_final_source
  CHECK (final_source IN ('STATUS_REPORT','INVESTIGATION')) NOT VALID;
