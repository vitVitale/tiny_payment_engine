-- Fraud assessments and the rule parameters (specs/14-risk-service.md §5, §6.1). One assessment per payment and
-- direction (assessment_id = <paymentId>:<direction>): a replay answers the stored row. The velocity indexes serve the
-- RiskContext of the velocity rules. Rules hold parameters only, the logic is code; every MVP rule is seeded with the
-- defaults of §6.1 (amounts in minor units, durations ISO 8601).
CREATE TABLE assessments (
  assessment_id VARCHAR(60)  PRIMARY KEY,
  payment_id    UUID         NOT NULL,
  direction     VARCHAR(8)   NOT NULL CHECK (direction IN ('OUTGOING', 'INCOMING')),
  debtor_iban   VARCHAR(34)  NOT NULL,
  creditor_iban VARCHAR(34)  NOT NULL,
  amount_minor  BIGINT       NOT NULL CHECK (amount_minor > 0),
  decision      VARCHAR(6)   NOT NULL CHECK (decision IN ('ALLOW', 'DENY', 'REVIEW')),
  reason_code   VARCHAR(4)   CHECK (reason_code IN ('FR01', 'RR04')),
  rule_hits     JSONB        NOT NULL,
  assessed_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX assessments_debtor_time ON assessments (debtor_iban, assessed_at);
CREATE INDEX assessments_creditor_time ON assessments (creditor_iban, assessed_at);

CREATE TABLE rules (
  rule_id    VARCHAR(40)  PRIMARY KEY,
  enabled    BOOLEAN      NOT NULL,
  direction  VARCHAR(8)   NOT NULL CHECK (direction IN ('OUTGOING', 'INCOMING', 'BOTH')),
  parameters JSONB        NOT NULL,
  action     VARCHAR(6)   NOT NULL CHECK (action IN ('DENY', 'REVIEW')),
  updated_at TIMESTAMPTZ  NOT NULL,
  updated_by VARCHAR(100) NOT NULL
);

INSERT INTO rules (rule_id, enabled, direction, parameters, action, updated_at, updated_by) VALUES
  ('AMOUNT_LIMIT',          TRUE, 'BOTH',     '{"maxAmountMinor": 5000000}', 'DENY', now(), 'V1__assessments_and_rules'),
  ('VELOCITY_COUNT_1H',     TRUE, 'OUTGOING', '{"maxCount": 20, "window": "PT1H"}', 'DENY', now(), 'V1__assessments_and_rules'),
  ('VELOCITY_AMOUNT_24H',   TRUE, 'OUTGOING', '{"maxAmountMinor": 10000000, "window": "PT24H"}', 'DENY', now(),
   'V1__assessments_and_rules'),
  ('NEW_PAYEE_HIGH_AMOUNT', TRUE, 'OUTGOING', '{"minAmountMinor": 500000}', 'REVIEW', now(), 'V1__assessments_and_rules'),
  ('MULE_NEW_ACCOUNT',      TRUE, 'INCOMING', '{"maxAccountAge": "P7D", "maxIncomingCount": 10, "window": "PT24H"}',
   'DENY', now(), 'V1__assessments_and_rules'),
  ('NON_EU_SANCTIONS',      TRUE, 'BOTH',     '{"threshold": 0.97, "lists": ["UN"]}', 'DENY', now(), 'V1__assessments_and_rules');
