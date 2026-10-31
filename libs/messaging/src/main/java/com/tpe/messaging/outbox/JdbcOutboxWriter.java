package com.tpe.messaging.outbox;

import com.tpe.contracts.ContractJson;
import com.tpe.contracts.Message;
import com.tpe.messaging.MessageHeaders;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.UuidV7Generator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link OutboxWriter} on the {@code outbox} table (specs/01-shared-libraries.md §3.1, §3.2): serialises with
 * {@link ContractJson}, which validates first, adds the headers of §3.4 and issues {@code NOTIFY tpe_outbox}, which
 * PostgreSQL delivers to the relay at commit. Inside a span, {@code traceparent} is that span, for the relay's
 * {@code send} span to continue (ADR-014).
 */
public final class JdbcOutboxWriter implements OutboxWriter {

    static final String CHANNEL = "tpe_outbox";

    private final JdbcTemplate jdbc;
    private final ContractJson json;
    private final UuidV7Generator eventIds;
    private final Clock clock;
    private final String producer;
    private final Spans spans;

    /** Without tracing: no {@code traceparent}. */
    public JdbcOutboxWriter(JdbcTemplate jdbc, ContractJson json, UuidV7Generator eventIds, Clock clock,
            String producer) {
        this(jdbc, json, eventIds, clock, producer, Spans.noop());
    }

    /** @param producer the service name, {@code tpe-producer} */
    public JdbcOutboxWriter(JdbcTemplate jdbc, ContractJson json, UuidV7Generator eventIds, Clock clock,
            String producer, Spans spans) {
        this.jdbc = jdbc;
        this.json = json;
        this.eventIds = eventIds;
        this.clock = clock;
        this.producer = producer;
        this.spans = spans;
    }

    @Override
    public UUID write(String topic, String key, Message message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.write needs an active transaction: the message must "
                    + "commit or roll back with the business change (specs/01-shared-libraries.md §3.2)");
        }
        String payload = json.write(message);
        UUID eventId = eventIds.next();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(MessageHeaders.EVENT_ID, eventId.toString());
        headers.put(MessageHeaders.MESSAGE_TYPE, message.type());
        headers.put(MessageHeaders.SCHEMA_VERSION, Integer.toString(message.schemaVersion()));
        headers.put(MessageHeaders.OCCURRED_AT, clock.instant().toString());
        headers.put(MessageHeaders.PRODUCER, producer);
        spans.currentTraceparent().ifPresent(traceparent -> headers.put(MessageHeaders.TRACEPARENT, traceparent));
        jdbc.update("""
                INSERT INTO outbox (event_id, topic, message_key, message_type, schema_version, payload, headers)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""",
                eventId, topic, key, message.type(), message.schemaVersion(), payload,
                OutboxHeaders.toJson(headers));
        jdbc.execute("NOTIFY " + CHANNEL);
        return eventId;
    }
}
