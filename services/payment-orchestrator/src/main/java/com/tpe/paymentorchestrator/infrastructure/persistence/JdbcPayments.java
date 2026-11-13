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
import com.tpe.paymentorchestrator.application.payments.Payments;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code payments}, {@code payment_transitions} and {@code payment_timers} in PostgreSQL. A new payment is due for
 * its first action at once: {@code next_action_at = accepted_at}.
 */
@Repository
class JdbcPayments implements Payments {

    private static final String COLUMNS = """
            payment_id, direction, state, reason_code, on_us, command_id, client_id, request_hash, tx_id, uetr,
            end_to_end_id, amount_minor, currency, debtor_name, debtor_iban, debtor_agent_bic, creditor_name,
            creditor_iban, creditor_agent_bic, remittance, vop_reference, vop_result, accepted_at, send_by,
            deadline_at, pending_action, finalised_at, trace_context""";

    private final JdbcClient jdbc;

    JdbcPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfNewCommand(Payment payment, Instant now) {
        return jdbc.sql("INSERT INTO payments (" + COLUMNS + """
                        , next_action_at, created_at, updated_at)
                        VALUES (:paymentId, :direction, :state, :reasonCode, :onUs, :commandId, :clientId, :requestHash,
                                :txId, CAST(:uetr AS UUID), :endToEndId, :amountMinor, :currency, :debtorName,
                                :debtorIban, :debtorAgentBic, :creditorName, :creditorIban, :creditorAgentBic,
                                :remittance, :vopReference, :vopResult, :acceptedAt, :sendBy, :deadlineAt,
                                :pendingAction, :finalisedAt, :traceContext, :acceptedAt, :now, :now)
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
