-- The account-holder projection of accounts.events (specs/14-risk-service.md §4, §5, ADR-007): one row per published
-- customer account, upserted by account_id when the revision is newer, deleted by the account's tombstone. The rules
-- read how new an account is (MULE_NEW_ACCOUNT, §6.1) by IBAN; screening will group accounts by customer. The
-- messaging inbox comes with the library (db/migration/messaging). A new table, so its indexes need no CONCURRENTLY.
-- UNKNOWN: a constant a newer ledger added, stored rather than refused (specs/01-shared-libraries.md §4).
CREATE TABLE account_holders (
  account_id      UUID         PRIMARY KEY,
  customer_id     VARCHAR(50)  NOT NULL,
  iban            VARCHAR(34)  NOT NULL UNIQUE,
  holder_type     VARCHAR(12)  NOT NULL CHECK (holder_type IN ('PERSON', 'ORGANISATION', 'UNKNOWN')),
  holder_name     VARCHAR(70)  NOT NULL,
  date_of_birth   DATE,
  country         CHAR(2),
  organisation_id VARCHAR(35),
  status          VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'BLOCKED', 'CLOSED', 'UNKNOWN')),
  opened_at       TIMESTAMPTZ  NOT NULL,
  revision        BIGINT       NOT NULL,
  updated_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX account_holders_customer ON account_holders (customer_id);
