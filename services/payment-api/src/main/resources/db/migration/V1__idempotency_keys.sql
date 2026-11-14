-- The idempotency guard's keys (specs/10-payment-api.md §5.1). payment_rejections comes with the XML intake.

CREATE TABLE idempotency_keys (
  client_id       VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(64)  NOT NULL,
  request_hash    CHAR(64)     NOT NULL,   -- SHA-256 of method + path + canonicalised body
  status          VARCHAR(12)  NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_status INT,
  response_body   JSONB,
  payment_id      UUID,
  created_at      TIMESTAMPTZ NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (client_id, idempotency_key)
);
CREATE INDEX idempotency_keys_expiry ON idempotency_keys (expires_at);
