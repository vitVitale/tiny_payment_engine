package com.tpe.paymentorchestrator.infrastructure.persistence;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.application.cases.ExceptionCases;
import com.tpe.paymentorchestrator.domain.payment.CaseResolution;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCase;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code exception_cases}, with the transitions and inbox entries of a case's payment, in PostgreSQL. */
@Repository
class JdbcExceptionCases implements ExceptionCases {

    private static final String COLUMNS = """
            case_id, payment_id, type, status, details::text AS details, opened_at, resolution, resolved_by,
            resolved_at, comment""";

    /** {@code csm.inbound.events}, the inbox's {@code topic} of the CSM events. */
    private static final String CSM_EVENTS = "csm.inbound.events";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {};

    private final JdbcClient jdbc;

    JdbcExceptionCases(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ExceptionCase> find(UUID caseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM exception_cases WHERE case_id = ?")
                .param(caseId).query(JdbcExceptionCases::exceptionCase).optional();
    }

    @Override
    public Optional<ExceptionCase> lock(UUID caseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM exception_cases WHERE case_id = ? FOR UPDATE")
                .param(caseId).query(JdbcExceptionCases::exceptionCase).optional();
    }

    @Override
    public Page list(ExceptionCase.Status status, ExceptionCaseType type, UUID after, int limit) {
        List<ExceptionCase> found = jdbc.sql("SELECT " + COLUMNS + """
                         FROM exception_cases
                        WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                          AND (CAST(:type AS VARCHAR) IS NULL OR type = :type)
                          AND (CAST(:after AS UUID) IS NULL OR case_id > :after)
                        ORDER BY case_id LIMIT :limit""")
                .param("status", status == null ? null : status.name())
                .param("type", type == null ? null : type.name())
                .param("after", after)
                .param("limit", limit + 1)
                .query(JdbcExceptionCases::exceptionCase).list();
        boolean more = found.size() > limit;
        return new Page(more ? found.subList(0, limit) : found, more);
    }

    @Override
    public void resolve(UUID caseId, CaseResolution resolution, String operator, String comment, Instant at) {
        jdbc.sql("""
                        UPDATE exception_cases
                        SET status = 'RESOLVED', resolution = ?, resolved_by = ?, resolved_at = ?, comment = ?
                        WHERE case_id = ? AND status = 'OPEN'""")
                .param(resolution.name())
                .param(operator)
                .param(Timestamp.from(at))
                .param(comment)
                .param(caseId)
                .update();
    }

    @Override
    public List<Transition> transitions(PaymentId paymentId) {
        return jdbc.sql("""
                        SELECT from_state, to_state, reason_code, cause, actor, at FROM payment_transitions
                        WHERE payment_id = ? ORDER BY id""")
                .param(paymentId.value())
                .query((rs, n) -> new Transition(rs.getString("from_state"), rs.getString("to_state"),
                        rs.getString("reason_code"), rs.getString("cause"), rs.getString("actor"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    @Override
    public List<CsmEvent> csmEvents(Bic debtorAgentBic, TxId txId) {
        // the dedup key <debtorAgentBic>:<txId>:<type>:<outcome> (specs/02-messaging-contracts.md §4)
        String prefix = debtorAgentBic.value() + ":" + txId.value() + ":";
        return jdbc.sql("""
                        SELECT dedup_key, event_id, processed_at FROM inbox
                        WHERE topic = ? AND starts_with(dedup_key, ?) ORDER BY processed_at, dedup_key""")
                .param(CSM_EVENTS)
                .param(prefix)
                .query((rs, n) -> {
                    String[] typeAndOutcome = rs.getString("dedup_key").substring(prefix.length()).split(":", 2);
                    return new CsmEvent(typeAndOutcome[0], typeAndOutcome.length > 1 ? typeAndOutcome[1] : null,
                            rs.getObject("event_id", UUID.class), rs.getTimestamp("processed_at").toInstant());
                })
                .list();
    }

    private static ExceptionCase exceptionCase(ResultSet rs, int row) throws SQLException {
        UUID paymentId = rs.getObject("payment_id", UUID.class);
        String resolution = rs.getString("resolution");
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new ExceptionCase(
                rs.getObject("case_id", UUID.class),
                paymentId == null ? null : new PaymentId(paymentId),
                ExceptionCaseType.valueOf(rs.getString("type")),
                ExceptionCase.Status.valueOf(rs.getString("status")),
                JSON.readValue(rs.getString("details"), DETAILS),
                rs.getTimestamp("opened_at").toInstant(),
                resolution == null ? null : CaseResolution.valueOf(resolution),
                rs.getString("resolved_by"),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                rs.getString("comment"));
    }
}
