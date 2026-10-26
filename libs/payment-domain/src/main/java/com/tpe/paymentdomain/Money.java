package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_AMOUNT;
import static com.tpe.paymentdomain.ValidationCode.UNSUPPORTED_CURRENCY;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount in minor units (euro cents), EUR only (specs/00-engineering-baseline.md §5). Positive unless created
 * by {@link #zero(Currency)}.
 */
public final class Money implements Comparable<Money> {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Pattern DECIMAL = Pattern.compile("([0-9]{1,12})(?:\\.([0-9]{1,2}))?");

    private final long minor;
    private final Currency currency;

    public Money(long minor, Currency currency) {
        this(minor, currency, false);
    }

    private Money(long minor, Currency currency, boolean allowZero) {
        Objects.requireNonNull(currency, "currency");
        if (!EUR.equals(currency)) {
            throw new DomainValidationException(UNSUPPORTED_CURRENCY, "only EUR is supported");
        }
        if (minor < 0 || (minor == 0 && !allowZero)) {
            throw new DomainValidationException(INVALID_AMOUNT, "amount must be positive");
        }
        this.minor = minor;
        this.currency = currency;
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency, true);
    }

    /** Parses an EUR decimal string such as {@code "123.45"}: 1–12 integer digits, 0–2 decimals, nothing else. */
    public static Money parse(String text) {
        Objects.requireNonNull(text, "text");
        var matcher = DECIMAL.matcher(text);
        if (!matcher.matches()) {
            throw new DomainValidationException(INVALID_AMOUNT, "not a decimal amount with at most 2 decimals");
        }
        String fraction = matcher.group(2) == null ? "" : matcher.group(2);
        long minor = Long.parseLong(matcher.group(1)) * 100 + Long.parseLong((fraction + "00").substring(0, 2));
        return new Money(minor, EUR);
    }

    public long minor() {
        return minor;
    }

    public Currency currency() {
        return currency;
    }

    public Money plus(Money other) {
        if (minor == 0) {
            return other;
        }
        try {
            return new Money(Math.addExact(minor, other.minor), currency);
        } catch (ArithmeticException e) {
            throw new DomainValidationException(INVALID_AMOUNT, "sum overflows");
        }
    }

    /** Rejects a result that is not positive: only {@link #zero(Currency)} holds nothing. */
    public Money minus(Money other) {
        return new Money(minor - other.minor, currency);
    }

    /** Always two decimals, e.g. {@code "1.50"}. */
    public String toDecimalString() {
        return String.format(Locale.ROOT, "%d.%02d", minor / 100, minor % 100);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(minor, other.minor);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && minor == other.minor && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minor, currency);
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency.getCurrencyCode();
    }
}
