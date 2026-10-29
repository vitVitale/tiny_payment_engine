package com.tpe.messaging.outbox;

import com.tpe.contracts.Message;
import java.util.UUID;

/**
 * Writes a message to the transactional outbox in the caller's business transaction (specs/01-shared-libraries.md
 * §3.2, ADR-002): it reaches Kafka if and only if the transaction commits.
 */
public interface OutboxWriter {

    /**
     * MUST be called inside an active transaction; throws {@link IllegalStateException} otherwise.
     *
     * @return the {@code eventId} of the message
     */
    UUID write(String topic, String key, Message message);
}
