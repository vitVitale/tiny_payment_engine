package com.tpe.csmgateway.infrastructure.persistence;

import static com.tpe.csmgateway.infrastructure.persistence.JdbcOutgoingTransfers.instant;
import static com.tpe.csmgateway.infrastructure.persistence.JdbcOutgoingTransfers.timestamp;

import com.tpe.contracts.Direction;
import com.tpe.csmgateway.application.investigation.StatusRequests;
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

/** {@code status_requests} (specs/15-csm-gateway.md §5); {@code :now} always comes from the caller's clock. */
@Repository
class JdbcStatusRequests implements StatusRequests {

    private final JdbcClient jdbc;

    JdbcStatusRequests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(StatusRequest request) {
        return jdbc.sql("""
                INSERT INTO status_requests (debtor_agent_bic, tx_id, direction, attempt, msg_id, created_at,
                  transmit_status, next_transmit_at)
                VALUES (:bic, :txId, :direction, :attempt, :msgId, :createdAt, 'PENDING', :createdAt)
                ON CONFLICT (debtor_agent_bic, tx_id, direction, attempt) DO NOTHING""")
                .param("bic", request.debtorAgentBic().value()).param("txId", request.txId().value())
                .param("direction", request.direction().name()).param("attempt", request.attempt())
                .param("msgId", request.msgId().value()).param("createdAt", timestamp(request.createdAt()))
                .update() == 1;
    }

    @Override
    public Optional<StatusRequest> findByMsgId(MsgId msgId) {
        return jdbc.sql("""
                SELECT debtor_agent_bic, tx_id, direction, attempt, msg_id, created_at FROM status_requests
                 WHERE msg_id = :msgId""")
                .param("msgId", msgId.value()).query(JdbcStatusRequests::row).optional();
    }

    @Override
    public List<MsgId> due(Instant now, int limit) {
        return jdbc.sql("""
                SELECT msg_id FROM status_requests
                 WHERE transmit_status = 'PENDING' AND next_transmit_at <= :now
                 ORDER BY next_transmit_at LIMIT :limit""")
                .param("now", timestamp(now)).param("limit", limit)
                .query(String.class).list().stream().map(MsgId::new).toList();
    }

    @Override
    public OptionalInt claim(MsgId msgId, Instant now, Duration lease, int maxAttempts) {
        return jdbc.sql("""
                UPDATE status_requests
                   SET transmit_attempts = transmit_attempts + 1, next_transmit_at = :leaseUntil
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING' AND next_transmit_at <= :now
                   AND transmit_attempts < :maxAttempts
                RETURNING transmit_attempts""")
                .param("leaseUntil", timestamp(now.plus(lease))).param("now", timestamp(now))
                .param("msgId", msgId.value()).param("maxAttempts", maxAttempts)
                .query(Integer.class).optional()
                .map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    public void delivered(MsgId msgId) {
        jdbc.sql("""
                UPDATE status_requests SET transmit_status = 'DELIVERED', next_transmit_at = NULL
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING'""")
                .param("msgId", msgId.value()).update();
    }

    @Override
    public void retryAt(MsgId msgId, int attempt, Instant at) {
        jdbc.sql("""
                UPDATE status_requests SET next_transmit_at = :at
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING' AND transmit_attempts = :attempt""")
                .param("at", timestamp(at)).param("msgId", msgId.value()).param("attempt", attempt)
                .update();
    }

    @Override
    public void giveUpIfSpent(MsgId msgId, Instant now, int maxAttempts) {
        jdbc.sql("""
                UPDATE status_requests SET transmit_status = 'GAVE_UP', next_transmit_at = NULL
                 WHERE msg_id = :msgId AND transmit_status = 'PENDING' AND next_transmit_at <= :now
                   AND transmit_attempts >= :maxAttempts""")
                .param("msgId", msgId.value()).param("now", timestamp(now)).param("maxAttempts", maxAttempts)
                .update();
    }

    private static StatusRequest row(ResultSet rs, int rowNum) throws SQLException {
        return new StatusRequest(new Bic(rs.getString("debtor_agent_bic")), new TxId(rs.getString("tx_id")),
                Direction.valueOf(rs.getString("direction")), rs.getInt("attempt"), new MsgId(rs.getString("msg_id")),
                instant(rs, "created_at"));
    }
}
