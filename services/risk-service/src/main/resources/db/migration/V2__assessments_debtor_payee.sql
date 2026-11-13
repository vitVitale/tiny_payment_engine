-- flyway:executeInTransaction=false
-- Whether a debtor has paid a creditor IBAN before (NEW_PAYEE_HIGH_AMOUNT, specs/14-risk-service.md §6.1): only
-- ALLOW OUTGOING assessments count, so only they are indexed. assessments is live, so the index is built
-- CONCURRENTLY, in its own migration (00 §9).
CREATE INDEX CONCURRENTLY IF NOT EXISTS assessments_debtor_payee ON assessments (debtor_iban, creditor_iban)
  WHERE direction = 'OUTGOING' AND decision = 'ALLOW';
