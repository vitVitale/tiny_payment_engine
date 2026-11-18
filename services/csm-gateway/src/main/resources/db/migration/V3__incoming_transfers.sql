-- specs/15-csm-gateway.md §5, §6.2: the pacs.008 the CSM delivers to us, one row per transaction (ADR-010), and the
-- answer the gateway sends for it.

CREATE TABLE incoming_transfers (
  debtor_agent_bic  VARCHAR(11) NOT NULL,          -- ADR-010: identity = (debtor_agent_bic, tx_id)
  tx_id             VARCHAR(35) NOT NULL,
  msg_id            VARCHAR(35) NOT NULL,
  received_at       TIMESTAMPTZ NOT NULL,
  answer_deadline   TIMESTAMPTZ NOT NULL,
  answer_status     VARCHAR(4) CHECK (answer_status IN ('ACCP','RJCT')),
  answer_reason     VARCHAR(4),
  answer_decided_by VARCHAR(10) CHECK (answer_decided_by IN ('VERDICT','DEADLINE','VALIDATION')),
  answered_at       TIMESTAMPTZ,
  answer_msg_id     VARCHAR(35) UNIQUE,
  transmit_status   VARCHAR(10) CHECK (transmit_status IN ('PENDING','DELIVERED','GAVE_UP')),
  transmit_attempts INT NOT NULL DEFAULT 0,
  next_transmit_at  TIMESTAMPTZ,
  final_status      VARCHAR(10) CHECK (final_status IN ('SETTLED','REJECTED')),
  final_reason      VARCHAR(35),
  final_at          TIMESTAMPTZ,
  trace_context     VARCHAR(55),                   -- root of the incoming payment trace (ADR-014)
  version           BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (debtor_agent_bic, tx_id)
);
CREATE INDEX incoming_unanswered ON incoming_transfers (answer_deadline) WHERE answer_status IS NULL;
-- the admin API looks a TxId up across debtor agents (15 §3.2)
CREATE INDEX incoming_tx ON incoming_transfers (tx_id);
CREATE INDEX incoming_transmit_due ON incoming_transfers (next_transmit_at) WHERE transmit_status = 'PENDING';
