package com.tpe.messaging;

import io.micrometer.tracing.Span;

/**
 * The attributes the relay's {@code send <topic>} and the listener's {@code process <topic>} spans share
 * (specs/26-observability.md §5.2). Technical attributes of the message, not business ones: identifiers and the
 * topic, never the payload (§5.3). A missing value leaves its attribute out.
 */
public final class MessageSpans {

    private MessageSpans() {}

    public static Span.Builder withMessage(Span.Builder span, String topic, String key, String eventId,
            String messageType) {
        tagIfPresent(span, "messaging.destination.name", topic);
        tagIfPresent(span, "messaging.kafka.message.key", key);
        tagIfPresent(span, "tpe.event.id", eventId);
        tagIfPresent(span, "tpe.message.type", messageType);
        return span;
    }

    private static void tagIfPresent(Span.Builder span, String name, String value) {
        if (value != null) {
            span.tag(name, value);
        }
    }
}
