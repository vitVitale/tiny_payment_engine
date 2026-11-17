-- specs/15-csm-gateway.md §5, §6.6: the pacs.028 the gateway sends, one per status request attempt of a transaction.

CREATE TABLE status_requests (
  debtor_agent_bic  VARCHAR(11) NOT NULL,        -- ADR-010; our own BIC for outgoing transfers
  tx_id             VARCHAR(35) NOT NULL,
  direction         VARCHAR(8) NOT NULL CHECK (direction IN ('OUTGOING','INCOMING')),
  attempt           INT NOT NULL,
  msg_id            VARCHAR(35) NOT NULL UNIQUE,
  created_at        TIMESTAMPTZ NOT NULL,
  transmit_status   VARCHAR(10) NOT NULL CHECK (transmit_status IN ('PENDING','DELIVERED','GAVE_UP')),
  transmit_attempts INT NOT NULL DEFAULT 0,
  next_transmit_at  TIMESTAMPTZ,
  PRIMARY KEY (debtor_agent_bic, tx_id, direction, attempt)
);
CREATE INDEX status_requests_transmit_due ON status_requests (next_transmit_at) WHERE transmit_status = 'PENDING';

-- the source of the recorded final status, so a status request re-publishes the final event as it was (§6.6)
-- NOT VALID: no scan of the existing rows under the lock (00 §9); new and updated rows are checked
ALTER TABLE outgoing_transfers ADD COLUMN final_source VARCHAR(13);
ALTER TABLE outgoing_transfers ADD CONSTRAINT outgoing_final_source
  CHECK (final_source IN ('STATUS_REPORT','INVESTIGATION')) NOT VALID;
