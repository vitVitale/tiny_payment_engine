package com.tpe.messaging.consumer;

import com.tpe.contracts.ContractJson;
import com.tpe.contracts.Message;
import com.tpe.messaging.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * Reading a consumed record (specs/01-shared-libraries.md §3.6): {@link #read} and {@link #readOneOf} deserialise
 * and run {@code ContractValidator} before the handler sees the message, so an invalid one fails with a
 * {@code ContractViolationException}, and a type the listener does not accept with an
 * {@link UnknownMessageTypeException}; {@link TpeErrorHandler} sends both to the DLT.
 */
public final class ConsumedMessages {

    private static final ContractJson JSON = new ContractJson();

    private ConsumedMessages() {}

    /** The validated message of {@code record}. */
    public static <T extends Message> T read(ConsumerRecord<String, String> record, Class<T> type) {
        return JSON.read(record.value(), type);
    }

    /**
     * The validated message of {@code record}, of the type among {@code accepted} that its {@code tpe-message-type}
     * header names; {@link UnknownMessageTypeException} if none does or the header is missing.
     */
    @SafeVarargs
    public static Message readOneOf(ConsumerRecord<String, String> record, Class<? extends Message>... accepted) {
        String messageType = optionalHeader(record, MessageHeaders.MESSAGE_TYPE);
        for (Class<? extends Message> type : accepted) {
            if (Message.contractOf(type).type().equals(messageType)) {
                return read(record, type);
            }
        }
        throw new UnknownMessageTypeException(record.topic(), messageType);
    }

    /** The {@code tpe-event-id} header. */
    public static UUID eventId(ConsumerRecord<?, ?> record) {
        return UUID.fromString(header(record, MessageHeaders.EVENT_ID));
    }

    /** A header as text; {@link IllegalArgumentException} if the record has none. */
    public static String header(ConsumerRecord<?, ?> record, String name) {
        String value = optionalHeader(record, name);
        if (value == null) {
            throw new IllegalArgumentException("record has no " + name + " header");
        }
        return value;
    }

    /** A header as text; {@code null} if the record has none. */
    public static String optionalHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
