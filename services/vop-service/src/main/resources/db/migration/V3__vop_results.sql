-- Verification results and their references (specs/13-vop-service.md §5, §6.1, UC-02 5): what payment-api may later
-- accept a vopReference for. Names are personal data: the retention job clears them 24 h after expiry and deletes the
-- row after 30 days (§6.3). The reference check reads by primary key. The retention job and its overdue gauge find
-- rows still named past retention through the partial expiry index, rows 30 days old through the creation index.
CREATE TABLE vop_results (
  vop_reference VARCHAR(40)  PRIMARY KEY,
  debtor_iban   VARCHAR(34)  NOT NULL,
  payee_iban    VARCHAR(34)  NOT NULL,
  payee_name    VARCHAR(70),
  result        VARCHAR(12)  NOT NULL CHECK (result IN ('MATCH', 'CLOSE_MATCH', 'NO_MATCH', 'NOT_POSSIBLE')),
  actual_name   VARCHAR(70),
  responded_by  VARCHAR(11)  NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL,
  expires_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX vop_results_named_expiry ON vop_results (expires_at)
  WHERE payee_name IS NOT NULL OR actual_name IS NOT NULL;
CREATE INDEX vop_results_created ON vop_results (created_at);
