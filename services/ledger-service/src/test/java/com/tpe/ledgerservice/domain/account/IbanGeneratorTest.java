package com.tpe.ledgerservice.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tpe.paymentdomain.Iban;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

/** specs/12-ledger-service.md §6.4. */
class IbanGeneratorTest {

    @Test
    void buildsDeIbanFromBankCodeAndZeroPaddedAccountNumber() {
        // the example of the German IBAN registry entry: 37040044 / 0532013000
        Iban iban = new IbanGenerator("37040044").generate(532013000L);

        assertThat(iban.value()).isEqualTo("DE89370400440532013000");
    }

    @Property
    void everyGeneratedIbanRoundTripsThroughIbanWithOurBankCode(
            @ForAll @NumericChars @StringLength(8) String bankCode,
            @ForAll @LongRange(min = 1, max = IbanGenerator.MAX_ACCOUNT_NUMBER) long accountNumber) {
        Iban generated = new IbanGenerator(bankCode).generate(accountNumber);

        Iban parsed = new Iban(generated.value());
        assertThat(parsed).isEqualTo(generated);
        assertThat(parsed.countryCode()).isEqualTo("DE");
        assertThat(parsed.bankCode()).isEqualTo(bankCode);
        assertThat(Long.parseLong(parsed.value().substring(12))).isEqualTo(accountNumber);
    }

    @Test
    void refusesABankCodeThatIsNotEightDigits() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IbanGenerator("1001001"));
        assertThatIllegalArgumentException().isThrownBy(() -> new IbanGenerator("1001001X"));
    }

    @Test
    void refusesAnAccountNumberBeyondTenDigits() {
        var generator = new IbanGenerator("10010010");

        assertThatIllegalStateException().isThrownBy(() -> generator.generate(IbanGenerator.MAX_ACCOUNT_NUMBER + 1));
        assertThatIllegalArgumentException().isThrownBy(() -> generator.generate(0));
    }
}
