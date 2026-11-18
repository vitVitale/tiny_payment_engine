package com.tpe.csmgateway.infrastructure.persistence;

import static com.tpe.csmgateway.infrastructure.persistence.JdbcOutgoingTransfers.instant;
import static com.tpe.csmgateway.infrastructure.persistence.JdbcOutgoingTransfers.timestamp;

import com.tpe.csmgateway.application.incoming.IncomingTransfers;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer.AnswerStatus;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer.DecidedBy;
import com.tpe.csmgateway.domain.transmit.TransmitStatus;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code incoming_transfers} (specs/15-csm-gateway.md §5); {@code :now} always comes from the caller's clock. */
@Repository
class JdbcIncomingTransfers implements IncomingTransfers {

    private static final String COLUMNS = """
            debtor_agent_bic, tx_id, msg_id, received_at, answer_deadline, answer_status, answer_reason,
            answer_decided_by, answered_at, answer_msg_id, transmit_status, transmit_attempts, next_transmit_at,
            trace_context""";

    private final JdbcClient jdbc;

    JdbcIncomingTransfers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(IncomingTransfer t) {
        return jdbc.sql("INSERT INTO incoming_transfers (" + COLUMNS + """
                ) VALUES (:bic, :txId, :msgId, :receivedAt, :answerDeadline, :answerStatus, :answerReason,
                  :answerDecidedBy, :answeredAt, :answerMsgId, :transmitStatus, :transmitAttempts, :nextTransmitAt,
                  :traceContext)
                ON CONFLICT (debtor_agent_bic, tx_id) DO NOTHING""")
                .param("bic", t.debtorAgentBic().value())
                .param("txId", t.txId().value())
                .param("msgId", t.msgId().value())
                .param("receivedAt", timestamp(t.receivedAt()))
                .param("answerDeadline", timestamp(t.answerDeadline()))
                .param("answerStatus", t.answerStatus() == null ? null : t.answerStatus().name())
                .param("answerReason", t.answerReason())
                .param("answerDecidedBy", t.answerDecidedBy() == null ? null : t.answerDecidedBy().name())
                .param("answeredAt", timestamp(t.answeredAt()))
                .param("answerMsgId", t.answerMsgId() == null ? null : t.answerMsgId().value())
                .param("transmitStatus", t.transmitStatus() == null ? null : t.transmitStatus().name())
                .param("transmitAttempts", t.transmitAttempts())
                .param("nextTransmitAt", timestamp(t.nextTransmitAt()))
                .param("traceContext", t.traceContext())
                .update() == 1;
    }

    @Override
    public Optional<IncomingTransfer> lock(Bic debtorAgentBic, TxId txId) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM incoming_transfers WHERE debtor_agent_bic = :bic AND tx_id = :txId FOR UPDATE")
                .param("bic", debtorAgentBic.value()).param("txId", txId.value())
                .query(JdbcIncomingTransfers::row).optional();
    }

    @Override
    public List<IncomingTransfer> find(TxId txId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incoming_transfers WHERE tx_id = :txId ORDER BY debtor_agent_bic")
                .param("txId", txId.value()).query(JdbcIncomingTransfers::row).list();
    }

    @Override
    public Optional<IncomingTransfer> findByAnswerMsgId(MsgId answerMsgId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incoming_transfers WHERE answer_msg_id = :msgId")
                .param("msgId", answerMsgId.value()).query(JdbcIncomingTransfers::row).optional();
    }

    @Override
    public void resendAnswer(Bic debtorAgentBic, TxId txId, Instant now) {
        jdbc.sql("""
                UPDATE incoming_transfers
                   SET transmit_status = 'PENDING', next_transmit_at = :now, version = version + 1
                 WHERE debtor_agent_bic = :bic AND tx_id = :txId AND answer_status IS NOT NULL""")
                .param("now", timestamp(now)).param("bic", debtorAgentBic.value()).param("txId", txId.value())
                .update();
    }

    @Override
    public List<MsgId> dueAnswers(Instant now, int limit) {
        return jdbc.sql("""
                SELECT answer_msg_id FROM incoming_transfers
                 WHERE transmit_status = 'PENDING' AND next_transmit_at <= :now
                 ORDER BY next_transmit_at LIMIT :limit""")
                .param("now", timestamp(now)).param("limit", limit)
                .query(String.class).list().stream().map(MsgId::new).toList();
    }

    @Override
    public OptionalInt claimAnswer(MsgId answerMsgId, Instant now, Duration lease, Duration window) {
        return jdbc.sql("""
                UPDATE incoming_transfers
                   SET transmit_attempts = transmit_attempts + 1, next_transmit_at = :leaseUntil,
                       version = version + 1
                 WHERE answer_msg_id = :msgId AND transmit_status = 'PENDING' AND next_transmit_at <= :now
                   AND answer_deadline > :windowStart
                RETURNING transmit_attempts""")
                .param("leaseUntil", timestamp(now.plus(lease))).param("msgId", answerMsgId.value())
                .param("now", timestamp(now)).param("windowStart", timestamp(now.minus(window)))
                .query(Integer.class).optional()
                .map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    public void answerDelivered(MsgId answerMsgId) {
        jdbc.sql("""
                UPDATE incoming_transfers
                   SET transmit_status = 'DELIVERED', next_transmit_at = NULL, version = version + 1
                 WHERE answer_msg_id = :msgId AND transmit_status = 'PENDING'""")
                .param("msgId", answerMsgId.value()).update();
    }

    @Override
    public void retryAnswerAt(MsgId answerMsgId, int attempt, Instant at) {
        jdbc.sql("""
                UPDATE incoming_transfers SET next_transmit_at = :at, version = version + 1
                 WHERE answer_msg_id = :msgId AND transmit_status = 'PENDING' AND transmit_attempts = :attempt""")
                .param("at", timestamp(at)).param("msgId", answerMsgId.value()).param("attempt", attempt)
                .update();
    }

    @Override
    public void giveUpAnswerIfExpired(MsgId answerMsgId, Instant now, Duration window) {
        jdbc.sql("""
                UPDATE incoming_transfers
                   SET transmit_status = 'GAVE_UP', next_transmit_at = NULL, version = version + 1
                 WHERE answer_msg_id = :msgId AND transmit_status = 'PENDING' AND next_transmit_at <= :now
                   AND answer_deadline <= :windowStart""")
                .param("msgId", answerMsgId.value()).param("now", timestamp(now))
                .param("windowStart", timestamp(now.minus(window)))
                .update();
    }

    private static IncomingTransfer row(ResultSet rs, int rowNum) throws SQLException {
        String answerStatus = rs.getString("answer_status");
        String decidedBy = rs.getString("answer_decided_by");
        String answerMsgId = rs.getString("answer_msg_id");
        String transmitStatus = rs.getString("transmit_status");
        return new IncomingTransfer(
                new Bic(rs.getString("debtor_agent_bic")),
                new TxId(rs.getString("tx_id")),
                new MsgId(rs.getString("msg_id")),
                instant(rs, "received_at"),
                instant(rs, "answer_deadline"),
                answerStatus == null ? null : AnswerStatus.valueOf(answerStatus),
                rs.getString("answer_reason"),
                decidedBy == null ? null : DecidedBy.valueOf(decidedBy),
                instant(rs, "answered_at"),
                answerMsgId == null ? null : new MsgId(answerMsgId),
                transmitStatus == null ? null : TransmitStatus.valueOf(transmitStatus),
                rs.getInt("transmit_attempts"),
                instant(rs, "next_transmit_at"),
                rs.getString("trace_context"));
    }
}
