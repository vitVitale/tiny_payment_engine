package com.tpe.csmgateway.infrastructure.persistence;

import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.domain.outgoing.FinalStatus;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.SendStatus;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.TransmitStatus;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code outgoing_transfers} (specs/15-csm-gateway.md §5); {@code :now} always comes from the caller's clock. */
@Repository
class JdbcOutgoingTransfers implements OutgoingTransfers {

    private static final String COLUMNS = """
            tx_id, payment_id, uetr, msg_id, accepted_at, send_by, deadline_at, send_status, not_sent_reason,
            journalled_at, transmit_status, transmit_attempts, next_transmit_at, delivered_at, final_status,
            final_reason, final_at, trace_context""";

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
                  :finalStatus, :finalReason, :finalAt, :traceContext)""")
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
    public void recordFinal(TxId txId, FinalStatus status, String reason, Instant at) {
        jdbc.sql("""
                UPDATE outgoing_transfers
                   SET final_status = :status, final_reason = :reason, final_at = :at, version = version + 1
                 WHERE tx_id = :txId AND final_status IS NULL""")
                .param("status", status.name()).param("reason", reason).param("at", timestamp(at))
                .param("txId", txId.value())
                .update();
    }

    @Override
    public boolean claimTransmission(TxId txId, Instant now, Duration lease) {
        return jdbc.sql("""
                UPDATE outgoing_transfers
                   SET transmit_attempts = transmit_attempts + 1, next_transmit_at = :leaseUntil,
                       version = version + 1
                 WHERE tx_id = :txId AND transmit_status = 'PENDING' AND next_transmit_at <= :now
                   AND send_by > :now""")
                .param("leaseUntil", timestamp(now.plus(lease))).param("now", timestamp(now))
                .param("txId", txId.value())
                .update() == 1;
    }

    @Override
    public void delivered(TxId txId, Instant at) {
        jdbc.sql("""
                UPDATE outgoing_transfers
                   SET transmit_status = 'DELIVERED', delivered_at = :at, next_transmit_at = NULL,
                       version = version + 1
                 WHERE tx_id = :txId AND transmit_status = 'PENDING'""")
                .param("at", timestamp(at)).param("txId", txId.value())
                .update();
    }

    @Override
    public Optional<OutgoingTransfer> find(TxId txId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outgoing_transfers WHERE tx_id = :txId")
                .param("txId", txId.value()).query(JdbcOutgoingTransfers::row).optional();
    }

    private static OutgoingTransfer row(ResultSet rs, int rowNum) throws SQLException {
        String transmitStatus = rs.getString("transmit_status");
        String finalStatus = rs.getString("final_status");
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
