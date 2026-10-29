package com.tpe.messaging;

/** Kafka record headers of every published message (specs/01-shared-libraries.md §3.4). */
public final class MessageHeaders {

    /** UUID v7 of the outbox row; consumers deduplicate on it unless the topic says otherwise. */
    public static final String EVENT_ID = "tpe-event-id";
    /** The {@code @Contract} type, e.g. {@code SendCreditTransfer}. */
    public static final String MESSAGE_TYPE = "tpe-message-type";
    public static final String SCHEMA_VERSION = "tpe-schema-version";
    /** ISO instant of the outbox insert. */
    public static final String OCCURRED_AT = "tpe-occurred-at";
    /** The producing service's {@code spring.application.name}. */
    public static final String PRODUCER = "tpe-producer";

    private MessageHeaders() {}
}
