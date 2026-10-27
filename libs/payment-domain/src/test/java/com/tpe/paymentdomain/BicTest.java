package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_BIC;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BicTest {

    @ParameterizedTest
    @ValueSource(strings = {"DEUTDEFF", "DEUTDEFFXXX", "TPEBDEFFXXX", "PEERDEFF500", "ABCDDE2A", "NWBKGB2L"})
    void acceptsEightOrElevenCharacters(String bic) {
        assertThat(new Bic(bic).value()).isEqualTo(bic);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "DEUTDEF", "DEUTDEFFX", "DEUTDEFFXX", "DEUTDEFFXXXX", // length
        "deutdeff", "DEUTdeFF", // lower case
        "DEU1DEFF", // digit in institution code
        "DEUTD1FF", // digit in country code
        "DEUTDEF!", "DEUTDEFFXX-", "DEUT DEFF", // other characters
    })
    void rejectsMalformedBic(String bic) {
        assertRejected(() -> new Bic(bic), INVALID_BIC);
    }

    @Test
    void bic8DropsTheBranchCode() {
        assertThat(new Bic("DEUTDEFFXXX").bic8()).isEqualTo(new Bic("DEUTDEFF"));
        assertThat(new Bic("DEUTDEFF500").bic8()).isEqualTo(new Bic("DEUTDEFF"));
        assertThat(new Bic("DEUTDEFF").bic8()).isEqualTo(new Bic("DEUTDEFF"));
    }

    @Test
    void countryCodeIsCharactersFiveAndSix() {
        assertThat(new Bic("DEUTDEFFXXX").countryCode()).isEqualTo("DE");
        assertThat(new Bic("NWBKGB2L").countryCode()).isEqualTo("GB");
    }

    @Test
    void equalByValue() {
        assertThat(new Bic("DEUTDEFFXXX")).isEqualTo(new Bic("DEUTDEFFXXX")).isNotEqualTo(new Bic("DEUTDEFF"));
    }
}
