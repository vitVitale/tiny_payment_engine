package com.tpe.vopservice.infrastructure.persistence;

import com.tpe.vopservice.application.responding.AuditedAnswer;
import com.tpe.vopservice.application.responding.ResponderAudit;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code responder_audit} in PostgreSQL; IBAN and name leave this class only as peppered hashes. */
@Repository
class JdbcResponderAudit implements ResponderAudit {

    private final JdbcClient jdbc;
    private final AuditPepper pepper;

    JdbcResponderAudit(JdbcClient jdbc, AuditPepper pepper) {
        this.jdbc = jdbc;
        this.pepper = pepper;
    }

    @Override
    public void record(AuditedAnswer answer) {
        jdbc.sql("""
                        INSERT INTO responder_audit (request_id, requesting_psp_bic, iban_hash, name_hash,
                                                     pepper_version, result, at)
                        VALUES (:requestId, :bic, :ibanHash, :nameHash, :pepperVersion, :result, :at)""")
                .param("requestId", answer.request().requestId())
                .param("bic", answer.request().requestingPsp().value())
                .param("ibanHash", pepper.hash(answer.request().iban().value()))
                .param("nameHash", pepper.hash(answer.request().name()))
                .param("pepperVersion", pepper.version())
                .param("result", answer.result().name())
                .param("at", Timestamp.from(answer.at()))
                .update();
    }
}
