package com.tpe.paymentdomain;

import java.util.Objects;

/**
 * Name of a payer or payee: 1–70 characters after trim, SEPA character set. Names are personal data and are never
 * logged, so {@link #toString()} hides the value (specs/26-observability.md §4.4).
 */
public record PartyName(String value) {

    public PartyName {
        value = SepaCharacterSet.requireText(Objects.requireNonNull(value, "value").trim(), 70);
    }

    @Override
    public String toString() {
        return "PartyName[***]";
    }
}
