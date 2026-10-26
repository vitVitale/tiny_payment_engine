package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_AMOUNT;
import static com.tpe.paymentdomain.ValidationCode.UNSUPPORTED_CURRENCY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void holdsMinorUnitsAndCurrency() {
        Money money = new Money(12345, EUR);

        assertThat(money.minor()).isEqualTo(12345);
        assertThat(money.currency()).isEqualTo(EUR);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveAmount(long minor) {
        assertRejected(() -> new Money(minor, EUR), INVALID_AMOUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "CHF", "GBP"})
    void rejectsAnyCurrencyButEur(String code) {
        assertRejected(() -> new Money(100, Currency.getInstance(code)), UNSUPPORTED_CURRENCY);
    }

    @Test
    void zeroIsTheOnlyWayToHoldNothing() {
        Money zero = Money.zero(EUR);

        assertThat(zero.minor()).isZero();
        assertThat(zero.currency()).isEqualTo(EUR);
        assertThat(zero.toDecimalString()).isEqualTo("0.00");
    }

    @Test
    void zeroRejectsAnyCurrencyButEur() {
        assertRejected(() -> Money.zero(Currency.getInstance("USD")), UNSUPPORTED_CURRENCY);
    }

    @Test
    void equalByAmountAndCurrency() {
        assertThat(new Money(500, EUR)).isEqualTo(new Money(500, EUR)).hasSameHashCodeAs(new Money(500, EUR));
        assertThat(new Money(500, EUR)).isNotEqualTo(new Money(501, EUR));
    }

    @Test
    void plusAddsAmounts() {
        assertThat(new Money(150, EUR).plus(new Money(275, EUR))).isEqualTo(new Money(425, EUR));
        assertThat(Money.zero(EUR).plus(new Money(1, EUR))).isEqualTo(new Money(1, EUR));
        assertThat(Money.zero(EUR).plus(Money.zero(EUR))).isEqualTo(Money.zero(EUR));
    }

    @Test
    void plusRejectsOverflow() {
        assertRejected(() -> new Money(Long.MAX_VALUE, EUR).plus(new Money(1, EUR)), INVALID_AMOUNT);
    }

    @Test
    void minusSubtractsAmounts() {
        assertThat(new Money(425, EUR).minus(new Money(275, EUR))).isEqualTo(new Money(150, EUR));
    }

    @Test
    void minusToNothingIsRejected() {
        assertRejected(() -> new Money(425, EUR).minus(new Money(425, EUR)), INVALID_AMOUNT);
    }

    @Test
    void minusBelowZeroIsRejected() {
        assertRejected(() -> new Money(100, EUR).minus(new Money(101, EUR)), INVALID_AMOUNT);
    }

    @Test
    void comparesByAmount() {
        assertThat(new Money(100, EUR)).isLessThan(new Money(101, EUR));
        assertThat(new Money(101, EUR)).isGreaterThan(new Money(100, EUR));
        assertThat(new Money(100, EUR)).isEqualByComparingTo(new Money(100, EUR));
        assertThat(Money.zero(EUR)).isLessThan(new Money(1, EUR));
    }

    @ParameterizedTest
    @CsvSource({
        "123.45,       12345",
        "0.01,         1",
        "1,            100",
        "1.5,          150",
        "007.10,       710",
        "999999999999.99, 99999999999999",
    })
    void parsesDecimalString(String text, long expectedMinor) {
        assertThat(Money.parse(text)).isEqualTo(new Money(expectedMinor, EUR));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", "1.", ".5", "1.234", "1000000000000", "1000000000000.00",
        "+1.00", "-1.00", "1e2", "1E2", "1 000.00", " 1.00", "1.00 ", "1,00", "0", "0.00",
        "١٢٣", "abc", "1..0", "0x10"
    })
    void parseRejectsMalformedOrNonPositiveAmounts(String text) {
        assertRejected(() -> Money.parse(text), INVALID_AMOUNT);
    }

    @ParameterizedTest
    @CsvSource({
        "12345, 123.45",
        "1,     0.01",
        "100,   1.00",
        "150,   1.50",
        "99999999999999, 999999999999.99",
    })
    void decimalStringAlwaysHasTwoDecimals(long minor, String expected) {
        assertThat(new Money(minor, EUR).toDecimalString()).isEqualTo(expected);
    }
}
