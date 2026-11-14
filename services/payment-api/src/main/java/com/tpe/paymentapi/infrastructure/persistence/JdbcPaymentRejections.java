package com.tpe.paymentapi.infrastructure.persistence;

import com.tpe.paymentapi.application.rejection.PaymentRejections;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code payment_rejections} in PostgreSQL (specs/10-payment-api.md §5.1), each method one auto-committed statement. */
public class JdbcPaymentRejections implements PaymentRejections {

    private final JdbcClient jdbc;

    public JdbcPaymentRejections(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(UUID rejectionId, String clientId, String xml, Instant expiresAt) {
        jdbc.sql("INSERT INTO payment_rejections (rejection_id, client_id, xml, expires_at) VALUES (?, ?, ?, ?)")
                .params(rejectionId, clientId, xml, utc(expiresAt))
                .update();
    }

    @Override
    public Optional<String> find(UUID rejectionId, String clientId, Instant now) {
        return jdbc.sql("""
                SELECT xml FROM payment_rejections WHERE rejection_id = ? AND client_id = ? AND expires_at >= ?""")
                .params(rejectionId, clientId, utc(now))
                .query(String.class)
                .optional();
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                DELETE FROM payment_rejections WHERE rejection_id IN (
                  SELECT rejection_id FROM payment_rejections WHERE expires_at < ? LIMIT ?)""")
                .params(utc(now), limit)
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
