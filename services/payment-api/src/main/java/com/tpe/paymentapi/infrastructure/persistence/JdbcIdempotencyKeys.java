package com.tpe.paymentapi.infrastructure.persistence;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.paymentapi.application.idempotency.IdempotencyKeys;
import com.tpe.paymentapi.application.idempotency.IdempotentRequest;
import com.tpe.paymentapi.application.mapping.CustomerStatus;
import com.tpe.paymentdomain.PaymentId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code idempotency_keys} in PostgreSQL (specs/10-payment-api.md §5.1), each method one auto-committed statement. A
 * {@code COMPLETED} row keeps the {@code 202}: {@code response_status} and the body as the controller writes it,
 * {@code { paymentId, status, internalState, acceptedAt }}; a stored payment has no {@code txId}.
 */
public class JdbcIdempotencyKeys implements IdempotencyKeys {

    private static final int ACCEPTED = 202;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcIdempotencyKeys(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean claim(IdempotentRequest request, Instant createdAt, Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, status, created_at, expires_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)
                ON CONFLICT (client_id, idempotency_key) DO NOTHING""")
                .params(request.clientId(), request.key(), request.requestHash(), utc(createdAt), utc(expiresAt))
                .update() == 1;
    }

    @Override
    public Optional<Entry> find(String clientId, String key) {
        return jdbc.sql("""
                SELECT request_hash, status, response_body::text AS body, created_at, expires_at
                  FROM idempotency_keys WHERE client_id = ? AND idempotency_key = ?""")
                .params(clientId, key)
                .query(this::entry)
                .optional();
    }

    @Override
    public boolean takeOver(IdempotentRequest request, Instant abandonedAt, Instant createdAt, Instant expiresAt) {
        return jdbc.sql("""
                UPDATE idempotency_keys SET request_hash = ?, created_at = ?, expires_at = ?
                 WHERE client_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND created_at = ?""")
                .params(request.requestHash(), utc(createdAt), utc(expiresAt), request.clientId(), request.key(),
                        utc(abandonedAt))
                .update() == 1;
    }

    @Override
    public void complete(IdempotentRequest request, Instant createdAt, PaymentAccepted payment) {
        jdbc.sql("""
                UPDATE idempotency_keys
                   SET status = 'COMPLETED', response_status = ?, response_body = ?::jsonb, payment_id = ?
                 WHERE client_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND created_at = ?""")
                .params(ACCEPTED, body(payment), payment.paymentId().value(), request.clientId(), request.key(),
                        utc(createdAt))
                .update();
    }

    @Override
    public void release(IdempotentRequest request, Instant createdAt) {
        jdbc.sql("""
                DELETE FROM idempotency_keys
                 WHERE client_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND created_at = ?""")
                .params(request.clientId(), request.key(), utc(createdAt))
                .update();
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                DELETE FROM idempotency_keys WHERE (client_id, idempotency_key) IN (
                  SELECT client_id, idempotency_key FROM idempotency_keys WHERE expires_at < ? LIMIT ?)""")
                .params(utc(now), limit)
                .update();
    }

    private Entry entry(ResultSet rs, int row) throws SQLException {
        String body = rs.getString("body");
        return new Entry(rs.getString("request_hash"), Status.valueOf(rs.getString("status")),
                body == null ? null : payment(body), instant(rs, "created_at"), instant(rs, "expires_at"));
    }

    private String body(PaymentAccepted payment) {
        ObjectNode body = json.createObjectNode();
        body.put("paymentId", payment.paymentId().value().toString());
        body.put("status", CustomerStatus.of(payment.state()).name());
        body.put("internalState", payment.state().name());
        body.put("acceptedAt", payment.acceptedAt().toString());
        return json.writeValueAsString(body);
    }

    private PaymentAccepted payment(String stored) {
        JsonNode body = json.readTree(stored);
        return new PaymentAccepted(new PaymentId(UUID.fromString(body.get("paymentId").asString())),
                PaymentState.valueOf(body.get("internalState").asString()), null,
                Instant.parse(body.get("acceptedAt").asString()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
