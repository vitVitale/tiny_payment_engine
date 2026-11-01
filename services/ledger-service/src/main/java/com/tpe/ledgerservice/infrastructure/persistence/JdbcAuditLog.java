package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.account.AuditLog;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcAuditLog implements AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void record(String actor, String action, UUID accountId, Map<String, ?> details, Instant at) {
        jdbc.sql("INSERT INTO audit_log (actor, action, account_id, details, at) VALUES (?, ?, ?, ?::jsonb, ?)")
                .params(actor, action, accountId, json.writeValueAsString(details), at.atOffset(ZoneOffset.UTC))
                .update();
    }
}
