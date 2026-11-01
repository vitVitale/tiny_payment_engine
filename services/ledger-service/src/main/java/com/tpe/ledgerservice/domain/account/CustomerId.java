package com.tpe.ledgerservice.domain.account;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** The bank's customer number from the core system: 1–50 characters {@code [A-Za-z0-9_-]} (12 §3.2). */
public record CustomerId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,50}");

    public CustomerId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidAccountDataException(
                    List.of(new AccountDataViolation("customerId", "INVALID_CUSTOMER_ID")));
        }
    }
}
