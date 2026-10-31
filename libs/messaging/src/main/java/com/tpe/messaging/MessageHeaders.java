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
    /**
     * W3C trace context: in the outbox, of the transaction that wrote the row; on Kafka, the relay's {@code send}
     * span (ADR-014).
     */
    public static final String TRACEPARENT = "traceparent";

    /** On a DLT record (01 §3.6): the failure's message; contract violations name fields and codes, never values. */
    public static final String DLT_REASON = "tpe-dlt-reason";
    /** On a DLT record: the failure's class name. */
    public static final String DLT_EXCEPTION = "tpe-dlt-exception";
    /** On a DLT record: the offset of the source record. */
    public static final String DLT_ORIGINAL_OFFSET = "tpe-dlt-original-offset";
    /** On a DLT record: the partition of the source record. */
    public static final String DLT_ORIGINAL_PARTITION = "tpe-dlt-original-partition";

    private MessageHeaders() {}
}
