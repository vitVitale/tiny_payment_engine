-- The responder's audit trail (specs/13-vop-service.md §5, §6.2 step 4, UC-03 postcondition): one row per answered
-- verification request. IBAN and name only as HMAC-SHA-256 keyed with a secret pepper, lower-case hex; pepper_version
-- names the pepper, so evidence lookups hash with the row's version after a rotation (ADR-017). Kept 13 months.
CREATE TABLE responder_audit (
  id                 BIGSERIAL    PRIMARY KEY,
  request_id         VARCHAR(64)  NOT NULL,
  requesting_psp_bic VARCHAR(11)  NOT NULL,
  iban_hash          CHAR(64)     NOT NULL,
  name_hash          CHAR(64)     NOT NULL,
  pepper_version     SMALLINT     NOT NULL,
  result             VARCHAR(12)  NOT NULL CHECK (result IN ('MATCH', 'CLOSE_MATCH', 'NO_MATCH', 'NOT_POSSIBLE')),
  at                 TIMESTAMPTZ  NOT NULL
);
