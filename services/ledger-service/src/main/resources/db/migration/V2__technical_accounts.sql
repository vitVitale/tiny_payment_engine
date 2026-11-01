-- The technical accounts (specs/12-ledger-service.md §5, ADR-005) with the fixed ids of contracts' TechnicalAccounts.
-- Their IBANs are generated like a customer's (§6.4): DE + mod 97-10 check digits + tpe.bank.bank-code (the Flyway
-- placeholder bankCode) + the next account number. "131400" is "DE00" moved behind the BBAN, D = 13, E = 14.
INSERT INTO accounts (account_id, iban, kind, technical_code, status, opened_at)
SELECT id::uuid,
       'DE' || lpad((98 - mod((bban || '131400')::numeric, 97))::text, 2, '0') || bban,
       'TECHNICAL', code, 'OPEN', now()
  FROM (SELECT id, code, '${bankCode}' || lpad(nextval('account_number_seq')::text, 10, '0') AS bban
          FROM (VALUES ('00000000-0000-7000-8000-000000000001', 'CSM_SETTLEMENT'),
                       ('00000000-0000-7000-8000-000000000002', 'FUNDING')) AS technical (id, code)) AS numbered;
