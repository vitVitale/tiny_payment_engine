package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_BIC;

import java.util.Objects;
import java.util.regex.Pattern;

/** Business Identifier Code of a PSP: 8 or 11 characters, institution, country, location and optional branch. */
public record Bic(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");

    public Bic {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new DomainValidationException(INVALID_BIC, "not an 8 or 11 character BIC");
        }
    }

    /** The BIC without its branch code; compare institutions with this, {@code DEUTDEFF} equals {@code DEUTDEFFXXX}. */
    public Bic bic8() {
        return value.length() == 8 ? this : new Bic(value.substring(0, 8));
    }
}
