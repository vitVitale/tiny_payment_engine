-- The pain.002 RJCT FF01 documents of XML clients (specs/10-payment-api.md §3.5, §5.1), kept 24 h.

CREATE TABLE payment_rejections (
  rejection_id UUID PRIMARY KEY,
  client_id    VARCHAR(100) NOT NULL,
  xml          TEXT         NOT NULL,
  expires_at   TIMESTAMPTZ  NOT NULL
);
-- not CONCURRENTLY (00 §9): the table is new and empty, so the index locks nothing for long
CREATE INDEX payment_rejections_expiry ON payment_rejections (expires_at);
