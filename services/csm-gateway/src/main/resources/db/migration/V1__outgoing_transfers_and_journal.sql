-- specs/15-csm-gateway.md §5: outgoing transfers and the message journal; outbox and inbox come from libs/messaging.
-- incoming_transfers and status_requests follow with the incoming flow and status requests.

CREATE TABLE outgoing_transfers (
  tx_id            VARCHAR(35) PRIMARY KEY,
  payment_id       UUID NOT NULL,
  uetr             UUID NOT NULL,
  msg_id           VARCHAR(35) NOT NULL UNIQUE,
  accepted_at      TIMESTAMPTZ NOT NULL,
  send_by          TIMESTAMPTZ NOT NULL,
  deadline_at      TIMESTAMPTZ NOT NULL,
  send_status      VARCHAR(10) NOT NULL CHECK (send_status IN ('SENT','NOT_SENT')),
  not_sent_reason  VARCHAR(4),
  journalled_at    TIMESTAMPTZ NOT NULL,
  transmit_status  VARCHAR(10) CHECK (transmit_status IN ('PENDING','DELIVERED','GAVE_UP')),
  transmit_attempts INT NOT NULL DEFAULT 0,
  next_transmit_at TIMESTAMPTZ,
  delivered_at     TIMESTAMPTZ,
  final_status     VARCHAR(10) CHECK (final_status IN ('SETTLED','REJECTED')),
  final_reason     VARCHAR(35),
  final_at         TIMESTAMPTZ,
  trace_context    VARCHAR(55),                  -- W3C traceparent of the payment trace (ADR-014)
  version          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX outgoing_transmit_due ON outgoing_transfers (next_transmit_at) WHERE transmit_status = 'PENDING';

CREATE TABLE message_journal (
  id          BIGSERIAL PRIMARY KEY,
  direction   VARCHAR(3) NOT NULL CHECK (direction IN ('IN','OUT')),
  message_type VARCHAR(20) NOT NULL,          -- pacs.008.001.08, …
  msg_id      VARCHAR(35),
  tx_id       VARCHAR(35),
  debtor_agent_bic VARCHAR(11),               -- with tx_id the transaction identity (ADR-010); NULL if unreadable
  xml         TEXT NOT NULL,
  valid       BOOLEAN NOT NULL,
  validation_errors JSONB,
  note        VARCHAR(200),                   -- e.g. 'late verdict ignored', 'duplicate pacs.008 dropped'
  at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX message_journal_tx ON message_journal (tx_id, id);
