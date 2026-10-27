package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_UETR;

import java.util.Objects;
import java.util.regex.Pattern;

/** Unique end-to-end transaction reference: a UUID v4 in lower case, as the ISO {@code UUIDv4Identifier}. */
public record Uetr(String value) {

    private static final Pattern UUID_V4 =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    public Uetr {
        Objects.requireNonNull(value, "value");
        if (!UUID_V4.matcher(value).matches()) {
            throw new DomainValidationException(INVALID_UETR, "not a lower-case UUID v4");
        }
    }
}
