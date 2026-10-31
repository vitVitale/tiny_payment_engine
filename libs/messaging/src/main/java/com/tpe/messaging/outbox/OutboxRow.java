package com.tpe.messaging.outbox;

import java.time.Duration;
import java.util.Map;

/**
 * An unpublished outbox row as the relay sends it: the Kafka record and its headers.
 *
 * @param age since the insert when it was read, on the database's clock
 */
public record OutboxRow(long id, String topic, String key, String payload, Map<String, String> headers,
        Duration age) {

    public OutboxRow {
        headers = Map.copyOf(headers);
    }
}
