-- The payment record, its transitions, timers and exception cases (specs/11-payment-orchestrator.md §5). outbox and
-- inbox come from the messaging library's migrations (V0_x, specs/01-shared-libraries.md §3).

CREATE TABLE payments (
  payment_id        UUID PRIMARY KEY,
  direction         VARCHAR(8)  NOT NULL CHECK (direction IN ('OUTGOING','INCOMING')),
  state             VARCHAR(20) NOT NULL CHECK (state IN ('RECEIVED','RISK_CHECKED','FUNDS_RESERVED',
                      'SENT_TO_CSM','ACCEPT_REQUESTED','ACCEPTED','INVESTIGATING','SETTLED','REJECTED')),
  reason_code       VARCHAR(4),
  on_us             BOOLEAN NOT NULL DEFAULT false,
  command_id        UUID UNIQUE,                 -- outgoing only
  client_id         VARCHAR(100),
  request_hash      CHAR(64),
  tx_id             VARCHAR(35) NOT NULL,
  uetr              UUID NOT NULL,
  end_to_end_id     VARCHAR(35) NOT NULL,
  msg_id            VARCHAR(35),                 -- incoming: pacs.008 MsgId
  amount_minor      BIGINT NOT NULL CHECK (amount_minor > 0),
  currency          CHAR(3) NOT NULL CHECK (currency = 'EUR'),
  debtor_name       VARCHAR(70) NOT NULL,
  debtor_iban       VARCHAR(34) NOT NULL,
  debtor_agent_bic  VARCHAR(11) NOT NULL,
  debtor_account_id UUID,
  creditor_name     VARCHAR(70) NOT NULL,
  creditor_iban     VARCHAR(34) NOT NULL,
  creditor_agent_bic VARCHAR(11) NOT NULL,
  creditor_account_id UUID,
  remittance        VARCHAR(140),
  vop_reference     VARCHAR(40),
  vop_result        VARCHAR(12) CHECK (vop_result IN ('MATCH','CLOSE_MATCH','NO_MATCH','NOT_POSSIBLE')),  -- outgoing
  accepted_at       TIMESTAMPTZ NOT NULL,        -- outgoing: our acceptance; incoming: gateway receipt
  send_by           TIMESTAMPTZ,                 -- outgoing
  deadline_at       TIMESTAMPTZ,                 -- outgoing 20 s deadline
  answer_deadline   TIMESTAMPTZ,                 -- incoming
  verdict           VARCHAR(6) CHECK (verdict IN ('ACCEPT','REJECT')),   -- incoming
  verdict_reason    VARCHAR(4),
  csm_outcome       VARCHAR(10) CHECK (csm_outcome IN ('SETTLED','REJECTED','NOT_SENT')),
  csm_outcome_at    TIMESTAMPTZ,
  booking_entry_id  UUID,
  pending_action    VARCHAR(30) CHECK (pending_action IN ('ASSESS_RISK','CREATE_HOLD','SEND','ASSESS_PAYEE_RISK',
                      'SETTLE_ON_US','SETTLE_HOLD','RELEASE_HOLD','CREATE_PENDING_CREDIT','CONFIRM_CREDIT',
                      'CANCEL_CREDIT')),
  next_action_at    TIMESTAMPTZ,
  action_attempts   INT NOT NULL DEFAULT 0,
  investigation_started_at TIMESTAMPTZ,          -- start of the current investigation; reset by RETRY_INVESTIGATION
  status_request_attempt INT NOT NULL DEFAULT 0, -- attempt of the last RequestPaymentStatus; never reset (§6.4)
  final_event_emitted BOOLEAN NOT NULL DEFAULT false,
  finalised_at      TIMESTAMPTZ,
  trace_context     VARCHAR(55),                 -- W3C traceparent of the payment trace (ADR-014); NULL: new root
  version           BIGINT NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  UNIQUE (direction, debtor_agent_bic, tx_id)          -- ADR-010
);
CREATE INDEX payments_due_action ON payments (next_action_at) WHERE pending_action IS NOT NULL;

CREATE TABLE payment_transitions (
  id BIGSERIAL PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payments,
  from_state VARCHAR(20) CHECK (from_state IN ('RECEIVED','RISK_CHECKED','FUNDS_RESERVED',
    'SENT_TO_CSM','ACCEPT_REQUESTED','ACCEPTED','INVESTIGATING','SETTLED','REJECTED')),
  to_state VARCHAR(20) NOT NULL CHECK (to_state IN ('RECEIVED','RISK_CHECKED','FUNDS_RESERVED',
    'SENT_TO_CSM','ACCEPT_REQUESTED','ACCEPTED','INVESTIGATING','SETTLED','REJECTED')),
  reason_code VARCHAR(4),
  cause VARCHAR(50) NOT NULL,          -- e.g. RISK_ALLOW, CSM_SETTLED, DEADLINE, OPERATOR
  actor VARCHAR(100) NOT NULL DEFAULT 'system', at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_timers (
  timer_id   BIGSERIAL PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments,
  type       VARCHAR(30) NOT NULL CHECK (type IN ('OUTGOING_DEADLINE','SETTLEMENT_OVERDUE','INVESTIGATION_RETRY')),
  due_at     TIMESTAMPTZ NOT NULL,
  attempt    INT NOT NULL DEFAULT 0,
  status     VARCHAR(10) NOT NULL CHECK (status IN ('SCHEDULED','FIRED','CANCELLED')),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_timers_due ON payment_timers (due_at) WHERE status = 'SCHEDULED';

CREATE TABLE exception_cases (
  case_id    UUID PRIMARY KEY, payment_id UUID REFERENCES payments,
  type       VARCHAR(40) NOT NULL CHECK (type IN ('UNRESOLVED_INVESTIGATION','CONTRADICTING_CSM_OUTCOME',
               'LEDGER_ACTION_STUCK','DLT_MESSAGE')),
  status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
  details    JSONB NOT NULL, opened_at TIMESTAMPTZ NOT NULL,
  resolution VARCHAR(30), resolved_by VARCHAR(100), resolved_at TIMESTAMPTZ, comment VARCHAR(1000)
);
CREATE UNIQUE INDEX exception_cases_one_open ON exception_cases (payment_id, type) WHERE status = 'OPEN';
