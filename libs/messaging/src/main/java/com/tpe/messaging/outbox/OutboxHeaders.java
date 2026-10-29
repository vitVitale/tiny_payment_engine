package com.tpe.messaging.outbox;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The {@code outbox.headers} JSONB column ↔ the record headers of specs/01-shared-libraries.md §3.4. */
final class OutboxHeaders {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private OutboxHeaders() {}

    static String toJson(Map<String, String> headers) {
        return JSON.writeValueAsString(headers);
    }

    static Map<String, String> fromJson(String json) {
        return JSON.readValue(json, MAP);
    }
}
