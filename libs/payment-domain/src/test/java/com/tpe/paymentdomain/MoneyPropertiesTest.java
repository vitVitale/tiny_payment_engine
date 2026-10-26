package com.tpe.paymentdomain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class MoneyPropertiesTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Property
    void parseOfDecimalStringIsIdentity(@ForAll @LongRange(min = 1, max = 99_999_999_999_999L) long minor) {
        Money money = new Money(minor, EUR);

        assertThat(Money.parse(money.toDecimalString())).isEqualTo(money);
    }
}
