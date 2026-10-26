package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The EPC basic Latin character set: {@code a-z A-Z 0-9 / - ? : ( ) . , ' +} and space. Text outside it is
 * rejected, never transliterated (specs/01-shared-libraries.md §1.1).
 */
public final class SepaCharacterSet {

    private static final Pattern ALLOWED = Pattern.compile("[a-zA-Z0-9/?:().,'+ -]*");

    private SepaCharacterSet() {}

    /** Returns {@code text} unchanged if every character is in the set. */
    public static String validate(String text) {
        Objects.requireNonNull(text, "text");
        if (!ALLOWED.matcher(text).matches()) {
            throw new DomainValidationException(INVALID_CHARACTERS, "text contains characters outside the SEPA set");
        }
        return text;
    }
}
