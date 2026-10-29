-- The sample service's business table: one row per business effect.
CREATE TABLE sample_effects (
  id         BIGSERIAL PRIMARY KEY,
  event_id   UUID        NOT NULL,
  payment_id UUID        NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
