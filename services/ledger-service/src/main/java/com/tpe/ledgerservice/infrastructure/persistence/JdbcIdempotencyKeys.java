package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.idempotency.IdempotencyKeys;
import com.tpe.ledgerservice.application.idempotency.IdempotentRequest;
import com.tpe.ledgerservice.application.idempotency.StoredResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyKeys implements IdempotencyKeys {

    private final JdbcClient jdbc;

    JdbcIdempotencyKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Stored> find(String key) {
        return jdbc.sql("""
                SELECT request_hash, response_status, response_body::text AS body
                  FROM idempotency_keys WHERE idempotency_key = ?""")
                .param(key)
                .query((rs, row) -> new Stored(rs.getString("request_hash"),
                        new StoredResponse(rs.getInt("response_status"), rs.getString("body"))))
                .optional();
    }

    /** {@code RETURNING} the JSONB as text: its key order is the store's, the one every replay has. */
    @Override
    public Optional<StoredResponse> insertIfAbsent(IdempotentRequest request, StoredResponse response,
            Instant createdAt) {
        return jdbc.sql("""
                INSERT INTO idempotency_keys (idempotency_key, request_hash, response_status, response_body, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING response_status, response_body::text AS body""")
                .params(request.key(), request.requestHash(), response.status(), response.body(),
                        createdAt.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> new StoredResponse(rs.getInt("response_status"), rs.getString("body")))
                .optional();
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM idempotency_keys WHERE idempotency_key IN (
                  SELECT idempotency_key FROM idempotency_keys WHERE created_at < ? LIMIT ?)""")
                .params(cutoff.atOffset(ZoneOffset.UTC), limit)
                .update();
    }
}
