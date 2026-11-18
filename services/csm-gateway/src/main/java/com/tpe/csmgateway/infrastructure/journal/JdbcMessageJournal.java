package com.tpe.csmgateway.infrastructure.journal;

import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code message_journal} (specs/15-csm-gateway.md §5): append-only. */
@Repository
class JdbcMessageJournal implements MessageJournal {

    private static final TypeReference<List<String>> CODES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcMessageJournal(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(Entry entry) {
        jdbc.sql("""
                INSERT INTO message_journal
                  (direction, message_type, msg_id, tx_id, debtor_agent_bic, xml, valid, validation_errors, note, at)
                VALUES (:direction, :messageType, :msgId, :txId, :debtorAgentBic, :xml, :valid,
                  CAST(:validationErrors AS JSONB), :note, :at)""")
                .param("direction", entry.direction().name())
                .param("messageType", entry.messageType())
                .param("msgId", entry.msgId())
                .param("txId", entry.txId() == null ? null : entry.txId().value())
                .param("debtorAgentBic", entry.debtorAgentBic() == null ? null : entry.debtorAgentBic().value())
                .param("xml", entry.xml())
                .param("valid", entry.valid())
                .param("validationErrors", entry.validationErrors().isEmpty() ? null
                        : json.writeValueAsString(entry.validationErrors()))
                .param("note", entry.note())
                .param("at", Timestamp.from(entry.at()))
                .update();
    }

    @Override
    public Optional<String> outboundXml(String msgId) {
        return jdbc.sql("""
                SELECT xml FROM message_journal
                 WHERE direction = 'OUT' AND msg_id = :msgId AND valid ORDER BY id LIMIT 1""")
                .param("msgId", msgId).query(String.class).optional();
    }

    @Override
    public List<Journalled> byTransaction(TxId txId, Optional<Bic> debtorAgentBic) {
        return jdbc.sql("""
                SELECT j.id, j.direction, j.message_type, j.msg_id, j.tx_id, j.debtor_agent_bic, j.xml, j.valid,
                       j.validation_errors::text AS validation_errors, j.note, j.at,
                       COALESCE(o.transmit_status, i.transmit_status) AS transmit_status
                  FROM message_journal j
                  LEFT JOIN outgoing_transfers o ON j.direction = 'OUT' AND o.msg_id = j.msg_id
                  LEFT JOIN incoming_transfers i ON j.direction = 'OUT' AND i.answer_msg_id = j.msg_id
                 WHERE j.tx_id = :txId AND (CAST(:bic AS VARCHAR) IS NULL OR j.debtor_agent_bic = :bic)
                 ORDER BY j.id""")
                .param("txId", txId.value())
                .param("bic", debtorAgentBic.map(Bic::value).orElse(null))
                .query(this::row).list();
    }

    private Journalled row(ResultSet rs, int rowNum) throws SQLException {
        String txId = rs.getString("tx_id");
        String bic = rs.getString("debtor_agent_bic");
        String errors = rs.getString("validation_errors");
        Entry entry = new Entry(Direction.valueOf(rs.getString("direction")), rs.getString("message_type"),
                rs.getString("msg_id"), txId == null ? null : new TxId(txId), bic == null ? null : new Bic(bic),
                rs.getString("xml"), rs.getBoolean("valid"), errors == null ? List.of() : json.readValue(errors, CODES),
                rs.getString("note"), rs.getTimestamp("at").toInstant());
        return new Journalled(rs.getLong("id"), entry, rs.getString("transmit_status"));
    }
}
