-- The double-entry journal (specs/12-ledger-service.md §5, ADR-005). The tables are new and empty, so their indexes need
-- no CONCURRENTLY (00 §9).
CREATE TABLE journal_entries (
  entry_id    UUID PRIMARY KEY,
  entry_type  VARCHAR(20) NOT NULL CHECK (entry_type IN ('PAYMENT_OUT','PAYMENT_IN','ON_US','FUNDING')),
  payment_id  UUID,
  tx_id       VARCHAR(35),
  created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE postings (
  posting_id  BIGSERIAL PRIMARY KEY,
  entry_id    UUID NOT NULL REFERENCES journal_entries,
  account_id  UUID NOT NULL REFERENCES accounts,
  side        CHAR(2) NOT NULL CHECK (side IN ('DR','CR')),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX postings_account_time ON postings (account_id, posting_id DESC);
-- the guard below sums the postings of one entry
CREATE INDEX postings_entry ON postings (entry_id);

-- Balanced-entry guard, the safety net behind the posting engine's own check: at commit, every journal entry a
-- transaction touched has at least 2 postings and Σ DR = Σ CR. Deferred, so an entry may be inserted posting by posting.
CREATE FUNCTION assert_entry_balanced(checked UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  n        BIGINT;
  debits   NUMERIC;
  credits  NUMERIC;
BEGIN
  -- an entry deleted together with its postings has nothing left to balance
  IF NOT EXISTS (SELECT 1 FROM journal_entries WHERE entry_id = checked) THEN
    RETURN;
  END IF;
  SELECT count(*), coalesce(sum(amount_minor) FILTER (WHERE side = 'DR'), 0),
         coalesce(sum(amount_minor) FILTER (WHERE side = 'CR'), 0)
    INTO n, debits, credits
    FROM postings WHERE entry_id = checked;
  IF n < 2 THEN
    RAISE EXCEPTION 'journal entry % has % posting(s), needs at least 2', checked, n
      USING ERRCODE = 'check_violation', CONSTRAINT = 'entry_balanced';
  END IF;
  IF debits <> credits THEN
    RAISE EXCEPTION 'journal entry % does not balance: DR %, CR %', checked, debits, credits
      USING ERRCODE = 'check_violation', CONSTRAINT = 'entry_balanced';
  END IF;
END $$;

CREATE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    PERFORM assert_entry_balanced(OLD.entry_id);
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    PERFORM assert_entry_balanced(NEW.entry_id);
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER postings_entry_balanced
  AFTER INSERT OR UPDATE OR DELETE ON postings
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

-- an entry without any posting fires no trigger on postings
CREATE CONSTRAINT TRIGGER journal_entries_balanced
  AFTER INSERT ON journal_entries
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
