package com.tpe.csmgateway.infrastructure.persistence;

import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.domain.outgoing.FinalStatus;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.SendStatus;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.TransmitStatus;
import com.tpe.csmgateway.domain.outgoing.StatusSource;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code outgoing_transfers} (specs/15-csm-gateway.md §5); {@code :now} always comes from the caller's clock. */
@Repository
class JdbcOutgoingTransfers implements OutgoingTransfers {

    private static final String COLUMNS = """
            tx_id, payment_id, uetr, msg_id, accepted_at, send_by, deadline_at, send_status, not_sent_reason,
            journalled_at, transmit_status, transmit_attempts, next_transmit_at, delivered_at, final_status,
            final_reason, final_at, final_source, trace_context""";

    private final JdbcClient jdbc;

    JdbcOutgoingTransfers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(TxId txId) {
        return jdbc.sql("SELECT count(*) FROM outgoing_transfers WHERE tx_id = :txId")
                .param("txId", txId.value()).query(Integer.class).single() > 0;
    }

    @Override
    public void insert(OutgoingTransfer t) {
        jdbc.sql("INSERT INTO outgoing_transfers (" + COLUMNS + """
                ) VALUES (:txId, :paymentId, :uetr, :msgId, :acceptedAt, :sendBy, :deadlineAt, :sendStatus,
                  :notSentReason, :journalledAt, :transmitStatus, :transmitAttempts, :nextTransmitAt, :deliveredAt,
                  :finalStatus, :finalReason, :finalAt, :finalSource, :traceContext)""")
                .param("txId", t.txId().value())
                .param("paymentId", t.paymentId().value())
                .param("uetr", t.uetr())
                .param("msgId", t.msgId())
                .param("acceptedAt", timestamp(t.acceptedAt()))
                .param("sendBy", timestamp(t.sendBy()))
                .param("deadlineAt", timestamp(t.deadlineAt()))
                .param("sendStatus", t.sendStatus().name())
                .param("notSentReason", t.notSentReason())
                .param("journalledAt", timestamp(t.journalledAt()))
                .param("transmitStatus", t.transmitStatus() == null ? null : t.transmitStatus().name())
                .param("transmitAttempts", t.transmitAttempts())
                .param("nextTransmitAt", timestamp(t.nextTransmitAt()))
                .param("deliveredAt", timestamp(t.deliveredAt()))
                .param("finalStatus", t.finalStatus() == null ? null : t.finalStatus().name())
                .param("finalReason", t.finalReason())
                .param("finalAt", timestamp(t.finalAt()))
                .param("finalSource", t.finalSource() == null ? null : t.finalSource().name())
                .param("traceContext", t.traceContext())
                .update();
    }

    @Override
    public Optional<OutgoingTransfer> lock(TxId txId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outgoing_transfers WHERE tx_id = :txId FOR UPDATE")
                .param("txId", txId.value()).query(JdbcOutgoingTransfers::row).optional();
    }

    @Override
    public Optional<OutgoingTransfer> lockByMsgId(String msgId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outgoing_transfers WHERE msg_id = :msgId FOR UPDATE")
                .param("msgId", msgId).query(JdbcOutgoingTransfers::row).optional();
    }

    @Override
    public void recordFinal(TxId txId, FinalStatus status, String reason, Instant at, StatusSource source) {
        jdbc.sql("""
                UPDATE outgoing_transfers
                   SET final_status = :status, final_reason = :reason, final_at = :at, final_source = :source,
                       version = version + 1
                 WHERE tx_id = :txId AND final_status IS NULL""")
                .param("status", status.name()).param("reason", reason).param("at", timestamp(at))
                .param("source", source.name())
                .param("txId", txId.value())
                .update();
    }

    @Override
    public List<MsgId> dueTransmissions(Instant now, int limit) {
        return jdbc.sql("""
                SELECT msg_id FROM outgoing_transfers
                 WHERE transmit_status = 'PENDING' AND next_transmit_at <= :now
                 ORDER BY next_transmit_at LIMIT :limit""")
                .param("now", timestamp(now)).param("limit", limit)
                .query(String.class).list().stream().map(MsgId::new).toList();
    }

    @Override
    public OptionalInt claimTransmission(MsgId msgId, Instant now, Duration lease) {
        return jdbc.sql("""
                UPDATE outgoing_transfers
                   SET transmit_attempts = transmit_attempts + 1, next_transmit_at = :leaseUntil,
                       version = version + 1
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING' AND next_transmit_at <= :now
                   AND send_by > :now
                RETURNING transmit_attempts""")
                .param("leaseUntil", timestamp(now.plus(lease))).param("now", timestamp(now))
                .param("msgId", msgId.value())
                .query(Integer.class).optional()
                .map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    public void delivered(MsgId msgId, Instant at) {
        jdbc.sql("""
                UPDATE outgoing_transfers
                   SET transmit_status = 'DELIVERED', delivered_at = :at, next_transmit_at = NULL,
                       version = version + 1
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING'""")
                .param("at", timestamp(at)).param("msgId", msgId.value())
                .update();
    }

    @Override
    public void retryAt(MsgId msgId, int attempt, Instant at) {
        jdbc.sql("""
                UPDATE outgoing_transfers SET next_transmit_at = :at, version = version + 1
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING' AND transmit_attempts = :attempt""")
                .param("at", timestamp(at)).param("msgId", msgId.value()).param("attempt", attempt)
                .update();
    }

    @Override
    public void gaveUp(MsgId msgId) {
        jdbc.sql("""
                UPDATE outgoing_transfers
                   SET transmit_status = 'GAVE_UP', next_transmit_at = NULL, version = version + 1
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING'""")
                .param("msgId", msgId.value())
                .update();
    }

    @Override
    public void gaveUpNotSent(MsgId msgId, ReasonCode reason) {
        jdbc.sql("""
                UPDATE outgoing_transfers
                   SET transmit_status = 'GAVE_UP', next_transmit_at = NULL, send_status = 'NOT_SENT',
                       not_sent_reason = :reason, version = version + 1
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING'""")
                .param("reason", reason.code()).param("msgId", msgId.value())
                .update();
    }

    @Override
    public Optional<OutgoingTransfer> find(TxId txId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outgoing_transfers WHERE tx_id = :txId")
                .param("txId", txId.value()).query(JdbcOutgoingTransfers::row).optional();
    }

    @Override
    public Optional<OutgoingTransfer> findByMsgId(MsgId msgId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outgoing_transfers WHERE msg_id = :msgId")
                .param("msgId", msgId.value()).query(JdbcOutgoingTransfers::row).optional();
    }

    private static OutgoingTransfer row(ResultSet rs, int rowNum) throws SQLException {
        String transmitStatus = rs.getString("transmit_status");
        String finalStatus = rs.getString("final_status");
        String finalSource = rs.getString("final_source");
        return new OutgoingTransfer(
                new TxId(rs.getString("tx_id")),
                new PaymentId(rs.getObject("payment_id", UUID.class)),
                rs.getObject("uetr", UUID.class),
                rs.getString("msg_id"),
                instant(rs, "accepted_at"),
                instant(rs, "send_by"),
                instant(rs, "deadline_at"),
                SendStatus.valueOf(rs.getString("send_status")),
                rs.getString("not_sent_reason"),
                instant(rs, "journalled_at"),
                transmitStatus == null ? null : TransmitStatus.valueOf(transmitStatus),
                rs.getInt("transmit_attempts"),
                instant(rs, "next_transmit_at"),
                instant(rs, "delivered_at"),
                finalStatus == null ? null : FinalStatus.valueOf(finalStatus),
                rs.getString("final_reason"),
                instant(rs, "final_at"),
                finalSource == null ? null : StatusSource.valueOf(finalSource),
                rs.getString("trace_context"));
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
