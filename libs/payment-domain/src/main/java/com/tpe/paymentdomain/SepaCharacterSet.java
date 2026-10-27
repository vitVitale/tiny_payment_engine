package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;
import static com.tpe.paymentdomain.ValidationCode.REQUIRED;
import static com.tpe.paymentdomain.ValidationCode.TOO_LONG;

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

    /** A non-blank text of at most {@code maxLength} characters in the set, as the ISO {@code MaxNText} types. */
    static String requireText(String text, int maxLength) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new DomainValidationException(REQUIRED, "text is empty");
        }
        if (text.length() > maxLength) {
            throw new DomainValidationException(TOO_LONG, "text is longer than " + maxLength + " characters");
        }
        return validate(text);
    }
}
