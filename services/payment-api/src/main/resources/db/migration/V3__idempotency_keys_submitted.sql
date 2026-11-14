-- A key whose submit may have reached the orchestrator keeps the reference check's result (specs/10-payment-api.md §5.1).

ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_status_check;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_status_check
  CHECK (status IN ('IN_PROGRESS','SUBMITTED','COMPLETED'));
ALTER TABLE idempotency_keys ADD COLUMN vop_result VARCHAR(12);
