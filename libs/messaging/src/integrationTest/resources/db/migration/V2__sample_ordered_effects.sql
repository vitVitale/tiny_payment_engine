-- The sample service's effects of sample.events, in the order they were applied (issue #15).
CREATE TABLE sample_ordered_effects (
  id          BIGSERIAL PRIMARY KEY,
  message_key TEXT NOT NULL,
  event_id    UUID NOT NULL,
  payment_id  UUID NOT NULL
);
