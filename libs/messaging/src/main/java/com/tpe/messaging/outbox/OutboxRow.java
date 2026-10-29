package com.tpe.messaging.outbox;

import java.util.Map;

/** An unpublished outbox row as the relay sends it: the Kafka record and its headers. */
public record OutboxRow(long id, String topic, String key, String payload, Map<String, String> headers) {

    public OutboxRow {
        headers = Map.copyOf(headers);
    }
}
