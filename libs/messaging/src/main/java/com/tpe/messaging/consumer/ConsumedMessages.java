package com.tpe.messaging.consumer;

import com.tpe.contracts.ContractJson;
import com.tpe.contracts.Message;
import com.tpe.messaging.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * Reading a consumed record (specs/01-shared-libraries.md §3.6): {@link #read} deserialises and runs
 * {@code ContractValidator} before the handler sees the message, so an invalid one fails with a
 * {@code ContractViolationException} (→ DLT, issue #15).
 */
public final class ConsumedMessages {

    private static final ContractJson JSON = new ContractJson();

    private ConsumedMessages() {}

    /** The validated message of {@code record}. */
    public static <T extends Message> T read(ConsumerRecord<String, String> record, Class<T> type) {
        return JSON.read(record.value(), type);
    }

    /** The {@code tpe-event-id} header. */
    public static UUID eventId(ConsumerRecord<?, ?> record) {
        return UUID.fromString(header(record, MessageHeaders.EVENT_ID));
    }

    /** A header as text; {@link IllegalArgumentException} if the record has none. */
    public static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            throw new IllegalArgumentException("record has no " + name + " header");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
