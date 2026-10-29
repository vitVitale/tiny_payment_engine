package com.tpe.observability.payment;

/**
 * The MDC keys of specs/00-engineering-baseline.md §11 and specs/26-observability.md §4.3. Inbound adapters set the
 * payment keys and propagate them; tracing sets {@link #TRACE_ID} and {@link #SPAN_ID}. The ECS log line carries every
 * MDC entry as a field, so a log line is findable by {@code paymentId}.
 */
public final class MdcKeys {

    public static final String PAYMENT_ID = "paymentId";
    public static final String TX_ID = "txId";
    public static final String UETR = "uetr";
    public static final String COMMAND_ID = "commandId";
    public static final String EVENT_ID = "eventId";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    private MdcKeys() {}
}
