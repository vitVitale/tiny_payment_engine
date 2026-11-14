package com.tpe.paymentorchestrator.infrastructure.persistence;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UuidV7Generator;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.payments.ActionClaim;
import com.tpe.paymentorchestrator.application.payments.LockedPayment;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code payments}, {@code payment_transitions}, {@code payment_timers} and {@code exception_cases} in PostgreSQL. A
 * new payment is due for its first action at once: {@code next_action_at = action_since = accepted_at}.
 */
@Repository
class JdbcPayments implements Payments {

    private static final String COLUMNS = """
            payment_id, direction, state, reason_code, on_us, command_id, client_id, request_hash, tx_id, uetr,
            end_to_end_id, amount_minor, currency, debtor_name, debtor_iban, debtor_agent_bic, creditor_name,
            creditor_iban, creditor_agent_bic, remittance, vop_reference, vop_result, accepted_at, send_by,
            deadline_at, pending_action, finalised_at, debtor_account_id, creditor_account_id, booking_entry_id,
            trace_context""";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    /** @param ids the case ids */
    JdbcPayments(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public boolean insertIfNewCommand(Payment payment, Instant now) {
        return jdbc.sql("INSERT INTO payments (" + COLUMNS + """
                        , next_action_at, action_since, created_at, updated_at)
                        VALUES (:paymentId, :direction, :state, :reasonCode, :onUs, :commandId, :clientId, :requestHash,
                                :txId, CAST(:uetr AS UUID), :endToEndId, :amountMinor, :currency, :debtorName,
                                :debtorIban, :debtorAgentBic, :creditorName, :creditorIban, :creditorAgentBic,
                                :remittance, :vopReference, :vopResult, :acceptedAt, :sendBy, :deadlineAt,
                                :pendingAction, :finalisedAt, :debtorAccountId, :creditorAccountId, :bookingEntryId,
                                :traceContext, :acceptedAt, :acceptedAt, :now, :now)
                        ON CONFLICT (command_id) DO NOTHING""")
                .param("paymentId", payment.paymentId().value())
                .param("direction", payment.direction().name())
                .param("state", payment.state().name())
                .param("reasonCode", payment.reasonCode() == null ? null : payment.reasonCode().code())
                .param("onUs", payment.onUs())
                .param("commandId", payment.commandId() == null ? null : payment.commandId().value())
                .param("clientId", payment.clientId())
                .param("requestHash", payment.requestHash())
                .param("txId", payment.txId().value())
                .param("uetr", payment.uetr().value())
                .param("endToEndId", payment.endToEndId().value())
                .param("amountMinor", payment.amount().minor())
                .param("currency", payment.amount().currency().getCurrencyCode())
                .param("debtorName", payment.debtor().name().value())
                .param("debtorIban", payment.debtor().iban().value())
                .param("debtorAgentBic", payment.debtor().agentBic().value())
                .param("creditorName", payment.creditor().name().value())
                .param("creditorIban", payment.creditor().iban().value())
                .param("creditorAgentBic", payment.creditor().agentBic().value())
                .param("remittance", payment.remittance() == null ? null : payment.remittance().value())
                .param("vopReference", payment.vopReference())
                .param("vopResult", payment.vopResult() == null ? null : payment.vopResult().name())
                .param("acceptedAt", timestamp(payment.acceptedAt()))
                .param("sendBy", timestamp(payment.sendBy()))
                .param("deadlineAt", timestamp(payment.deadlineAt()))
                .param("pendingAction", payment.pendingAction() == null ? null : payment.pendingAction().name())
                .param("finalisedAt", timestamp(payment.finalisedAt()))
                .param("debtorAccountId", payment.debtorAccountId())
                .param("creditorAccountId", payment.creditorAccountId())
                .param("bookingEntryId", payment.bookingEntryId())
                .param("traceContext", payment.traceContext())
                .param("now", timestamp(now))
                .update() == 1;
    }

    @Override
    public Optional<Payment> find(PaymentId paymentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE payment_id = ?")
                .param(paymentId.value())
                .query(JdbcPayments::payment)
                .optional();
    }

    @Override
    public Optional<Payment> findByCommand(CommandId commandId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE command_id = ?")
                .param(commandId.value())
                .query(JdbcPayments::payment)
                .optional();
    }

    @Override
    public void recordTransition(PaymentId paymentId, PaymentState from, PaymentState to, StatusReason reason,
            String cause, Instant at) {
        jdbc.sql("""
                        INSERT INTO payment_transitions (payment_id, from_state, to_state, reason_code, cause, at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .param(paymentId.value())
                .param(from == null ? null : from.name())
                .param(to.name())
                .param(reason == null ? null : reason.code())
                .param(cause)
                .param(timestamp(at))
                .update();
    }

    @Override
    public void scheduleTimer(PaymentId paymentId, TimerType type, Instant dueAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO payment_timers (payment_id, type, due_at, status, created_at)
                        VALUES (?, ?, ?, 'SCHEDULED', ?)""")
                .param(paymentId.value())
                .param(type.name())
                .param(timestamp(dueAt))
                .param(timestamp(now))
                .update();
    }

    @Override
    public void markTimerFired(long timerId) {
        jdbc.sql("UPDATE payment_timers SET status = 'FIRED' WHERE timer_id = ? AND status = 'SCHEDULED'")
                .param(timerId)
                .update();
    }

    @Override
    public Optional<LockedPayment> lockForTimer(PaymentId paymentId) {
        return jdbc.sql("SELECT " + COLUMNS + ", version, csm_outcome FROM payments WHERE payment_id = ? FOR UPDATE")
                .param(paymentId.value())
                .query((rs, n) -> new LockedPayment(payment(rs, n), rs.getLong("version"),
                        rs.getString("csm_outcome") != null))
                .optional();
    }

    @Override
    public void applyTimer(LockedPayment locked, Payment next, boolean finalEventEmitted, Instant now) {
        int updated = store(next, finalEventEmitted, now, "payment_id = :paymentId AND version = :version")
                .param("paymentId", next.paymentId().value())
                .param("version", locked.version())
                .update();
        if (updated != 1) {
            // the row is locked by this transaction, so its version cannot have moved
            throw new IllegalStateException("payment " + next.paymentId().value() + " changed under its lock");
        }
    }

    @Override
    public Optional<ActionClaim> claim(PaymentId paymentId, Set<PendingAction> actions, Instant now,
            Instant leaseUntil) {
        if (actions.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE payments
                        SET next_action_at = :leaseUntil, action_attempts = action_attempts + 1,
                            action_since = coalesce(action_since, :now),
                            version = version + 1, updated_at = :now
                        WHERE payment_id = :paymentId AND pending_action IN (:actions) AND next_action_at <= :now
                        RETURNING state, pending_action, action_attempts, version, action_since""")
                .param("leaseUntil", timestamp(leaseUntil))
                .param("now", timestamp(now))
                .param("paymentId", paymentId.value())
                .param("actions", actions.stream().map(Enum::name).toList())
                .query((rs, n) -> new ActionClaim(paymentId, PaymentState.valueOf(rs.getString("state")),
                        PendingAction.valueOf(rs.getString("pending_action")), rs.getInt("action_attempts"),
                        rs.getLong("version"), instant(rs, "action_since")))
                .optional();
    }

    @Override
    public boolean applyStep(ActionClaim claim, Payment next, boolean finalEventEmitted, Instant now) {
        return store(next, finalEventEmitted, now, """
                        payment_id = :paymentId AND state = :claimedState AND pending_action = :claimedAction
                          AND version = :version""")
                .param("paymentId", claim.paymentId().value())
                .param("claimedState", claim.state().name())
                .param("claimedAction", claim.action().name())
                .param("version", claim.version())
                .update() == 1;
    }

    /** The next state, action and ledger ids of a payment; a new action is due, and pending since, {@code now}. */
    private JdbcClient.StatementSpec store(Payment next, boolean finalEventEmitted, Instant now, String where) {
        return jdbc.sql("""
                        UPDATE payments
                        SET state = :state, reason_code = :reasonCode, pending_action = :pendingAction,
                            next_action_at = :nextActionAt, action_since = :nextActionAt, action_attempts = 0,
                            finalised_at = :finalisedAt,
                            debtor_account_id = :debtorAccountId, creditor_account_id = :creditorAccountId,
                            booking_entry_id = :bookingEntryId,
                            final_event_emitted = final_event_emitted OR :finalEventEmitted,
                            version = version + 1, updated_at = :now
                        WHERE\s""" + where)
                .param("state", next.state().name())
                .param("reasonCode", next.reasonCode() == null ? null : next.reasonCode().code())
                .param("pendingAction", next.pendingAction() == null ? null : next.pendingAction().name())
                .param("nextActionAt", next.pendingAction() == null ? null : timestamp(now))
                .param("finalisedAt", timestamp(next.finalisedAt()))
                .param("debtorAccountId", next.debtorAccountId())
                .param("creditorAccountId", next.creditorAccountId())
                .param("bookingEntryId", next.bookingEntryId())
                .param("finalEventEmitted", finalEventEmitted)
                .param("now", timestamp(now));
    }

    @Override
    public void retryAt(ActionClaim claim, Instant at, Instant now) {
        jdbc.sql("""
                        UPDATE payments SET next_action_at = :at, version = version + 1, updated_at = :now
                        WHERE payment_id = :paymentId AND pending_action = :action AND version = :version""")
                .param("at", timestamp(at))
                .param("now", timestamp(now))
                .param("paymentId", claim.paymentId().value())
                .param("action", claim.action().name())
                .param("version", claim.version())
                .update();
    }

    @Override
    public boolean openCase(PaymentId paymentId, ExceptionCaseType type, Map<String, Object> details, Instant now) {
        return jdbc.sql("""
                        INSERT INTO exception_cases (case_id, payment_id, type, status, details, opened_at)
                        VALUES (?, ?, ?, 'OPEN', CAST(? AS JSONB), ?)
                        ON CONFLICT (payment_id, type) WHERE status = 'OPEN' DO NOTHING""")
                .param(ids.next())
                .param(paymentId.value())
                .param(type.name())
                .param(JSON.writeValueAsString(details))
                .param(timestamp(now))
                .update() == 1;
    }

    private static Payment payment(ResultSet rs, int row) throws SQLException {
        UUID commandId = rs.getObject("command_id", UUID.class);
        String reasonCode = rs.getString("reason_code");
        String remittance = rs.getString("remittance");
        String vopResult = rs.getString("vop_result");
        String pendingAction = rs.getString("pending_action");
        return new Payment(
                new PaymentId(rs.getObject("payment_id", UUID.class)),
                PaymentDirection.valueOf(rs.getString("direction")),
                PaymentState.valueOf(rs.getString("state")),
                reasonCode == null ? null : ReasonCode.parse(reasonCode),
                rs.getBoolean("on_us"),
                commandId == null ? null : new CommandId(commandId),
                rs.getString("client_id"),
                rs.getString("request_hash"),
                new TxId(rs.getString("tx_id")),
                new Uetr(rs.getObject("uetr", UUID.class).toString()),
                new EndToEndId(rs.getString("end_to_end_id")),
                new Money(rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency"))),
                new PaymentParty(new PartyName(rs.getString("debtor_name")), new Iban(rs.getString("debtor_iban")),
                        new Bic(rs.getString("debtor_agent_bic"))),
                new PaymentParty(new PartyName(rs.getString("creditor_name")),
                        new Iban(rs.getString("creditor_iban")), new Bic(rs.getString("creditor_agent_bic"))),
                remittance == null ? null : new RemittanceInformation(remittance),
                rs.getString("vop_reference"),
                vopResult == null ? null : PayeeVerification.valueOf(vopResult),
                instant(rs, "accepted_at"),
                instant(rs, "send_by"),
                instant(rs, "deadline_at"),
                pendingAction == null ? null : PendingAction.valueOf(pendingAction),
                instant(rs, "finalised_at"),
                rs.getObject("debtor_account_id", UUID.class),
                rs.getObject("creditor_account_id", UUID.class),
                rs.getObject("booking_entry_id", UUID.class),
                rs.getString("trace_context"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
