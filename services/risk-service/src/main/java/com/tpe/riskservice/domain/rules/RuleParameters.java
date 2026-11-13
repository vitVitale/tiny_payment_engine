package com.tpe.riskservice.domain.rules;

import java.math.BigInteger;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * A rule's parameters, the {@code rules.parameters} JSON object (specs/14-risk-service.md §5): numbers as the JSON
 * reader gave them. A missing or ill-typed parameter is a configuration error, raised when the rule reads it.
 */
public record RuleParameters(Map<String, Object> values) {

    public static final RuleParameters NONE = new RuleParameters(Map.of());

    public RuleParameters {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    /** The non-negative ISO 8601 duration {@code name}, e.g. {@code "PT1H"}. */
    public Duration duration(String name) {
        if (values.get(name) instanceof String text) {
            try {
                Duration duration = Duration.parse(text);
                if (!duration.isNegative()) {
                    return duration;
                }
            } catch (DateTimeParseException e) {
                // reported below
            }
        }
        throw new IllegalStateException(
                "rule parameter " + name + " must be a non-negative ISO 8601 duration, is " + values.get(name));
    }

    /** The whole number {@code name}. */
    public long longValue(String name) {
        Object value = values.get(name);
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big && big.bitLength() < 64) {
            return big.longValue();
        }
        throw new IllegalStateException("rule parameter " + name + " must be a whole number, is " + value);
    }
}
