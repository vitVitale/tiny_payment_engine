-- Consumer deduplication (specs/01-shared-libraries.md §3.1, §3.6). Library migration: version V0_<n> (00 §9).
CREATE TABLE inbox (
  dedup_key    VARCHAR(200) PRIMARY KEY,
  topic        VARCHAR(100) NOT NULL,
  event_id     UUID         NOT NULL,
  processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
