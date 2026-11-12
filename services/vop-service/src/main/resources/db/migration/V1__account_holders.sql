-- The account-holder projection of accounts.events (specs/13-vop-service.md §4, §5, ADR-007): one row per published
-- customer account, upserted by account_id when the revision is newer, deleted by the account's tombstone. The
-- responder looks holders up by IBAN (§6.2). The messaging inbox comes with the library (db/migration/messaging).
-- UNKNOWN: a constant a newer ledger added, stored rather than refused (specs/01-shared-libraries.md §4).
CREATE TABLE account_holders (
  account_id      UUID PRIMARY KEY,
  iban            VARCHAR(34)  NOT NULL UNIQUE,
  holder_type     VARCHAR(12)  NOT NULL CHECK (holder_type IN ('PERSON', 'ORGANISATION', 'UNKNOWN')),
  holder_name     VARCHAR(70)  NOT NULL,
  normalised_name VARCHAR(140) NOT NULL,
  organisation_id VARCHAR(35),
  status          VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'BLOCKED', 'CLOSED', 'UNKNOWN')),
  revision        BIGINT       NOT NULL,
  updated_at      TIMESTAMPTZ  NOT NULL
);
