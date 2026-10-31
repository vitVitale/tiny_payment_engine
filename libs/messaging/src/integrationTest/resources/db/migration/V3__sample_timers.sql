-- The sample service's timers, fired by a PollingWorker (issue #17).
CREATE TABLE sample_timers (
  id      BIGSERIAL PRIMARY KEY,
  due_at  TIMESTAMPTZ NOT NULL,
  status  VARCHAR(10) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'FIRED'))
);
