package com.tpe.messaging.consumer;

/**
 * A consumed record whose {@code tpe-message-type} header names no type the listener accepts, or that has none.
 * Not retried: {@link TpeErrorHandler} sends it to the DLT (specs/01-shared-libraries.md §3.6).
 */
public final class UnknownMessageTypeException extends RuntimeException {

    private final String messageType;

    /** {@code messageType} is {@code null} if the record has no {@code tpe-message-type} header. */
    public UnknownMessageTypeException(String topic, String messageType) {
        super(messageType == null
                ? "record on " + topic + " has no tpe-message-type header"
                : "unknown message type " + messageType + " on " + topic);
        this.messageType = messageType;
    }

    /** The header value, {@code null} if there was none. */
    public String messageType() {
        return messageType;
    }
}
