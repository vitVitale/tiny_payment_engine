package com.tpe.pactsample.consumer;

import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** The sample consumer's message mapping (issue #20): a Kafka record's value and headers → the event it handles. */
public final class SampleAccountEvents {

    public record AccountOpened(UUID accountId, String status) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SampleAccountEvents() {}

    /** Only {@code tpe-message-type: AccountOpened} is accepted. */
    public static AccountOpened read(byte[] value, Map<String, ?> headers) {
        Object type = headers.get("tpe-message-type");
        if (!"AccountOpened".equals(type)) {
            throw new IllegalArgumentException("unexpected tpe-message-type " + type);
        }
        return JSON.readValue(value, AccountOpened.class);
    }
}
