-- Transactional outbox (specs/01-shared-libraries.md §3.1, ADR-002). Library migration: version V0_<n> (00 §9).
CREATE TABLE outbox (
  id             BIGSERIAL PRIMARY KEY,
  event_id       UUID         NOT NULL UNIQUE,
  topic          VARCHAR(100) NOT NULL,
  message_key    VARCHAR(100) NOT NULL,
  message_type   VARCHAR(100) NOT NULL,
  schema_version INT          NOT NULL,
  payload        JSONB        NOT NULL,
  headers        JSONB        NOT NULL DEFAULT '{}',
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
