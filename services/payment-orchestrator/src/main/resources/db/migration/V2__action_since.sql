-- When the pending action was set (specs/11-payment-orchestrator.md §5, §6.3): tpe.runner.stuck-after counts from
-- here before a ledger action retried forever opens a LEDGER_ACTION_STUCK case. Claims and retries leave it alone.
ALTER TABLE payments ADD COLUMN action_since TIMESTAMPTZ;
UPDATE payments SET action_since = updated_at WHERE pending_action IS NOT NULL;
