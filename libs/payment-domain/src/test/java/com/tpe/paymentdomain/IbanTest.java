package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_IBAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

    @Test
    void normalisesToUpperCaseWithoutSpaces() {
        assertThat(new Iban("de89 3704 0044 0532 0130 00").value()).isEqualTo("DE89370400440532013000");
    }

    @Test
    void equalByNormalisedValue() {
        assertThat(new Iban("DE89 3704 0044 0532 0130 00")).isEqualTo(new Iban("DE89370400440532013000"));
    }

    // The example IBAN of every SEPA country in the SWIFT IBAN registry, with its bank identifier
    @ParameterizedTest
    @CsvSource({
        "AD1200012030200359100100, AD, 0001",
        "AT611904300234573201, AT, 19043",
        "BE68539007547034, BE, 539",
        "BG80BNBG96611020345678, BG, BNBG",
        "CH9300762011623852957, CH, 00762",
        "CY17002001280000001200527600, CY, 002",
        "CZ6508000000192000145399, CZ, 0800",
        "DE89370400440532013000, DE, 37040044",
        "DK5000400440116243, DK, 0040",
        "EE382200221020145685, EE, 22",
        "ES9121000418450200051332, ES, 2100",
        "FI2112345600000785, FI, 123",
        "FR1420041010050500013M02606, FR, 20041",
        "GB29NWBK60161331926819, GB, NWBK",
        "GI75NWBK000000007099453, GI, NWBK",
        "GR1601101250000000012300695, GR, 011",
        "HR1210010051863000160, HR, 1001005",
        "HU42117730161111101800000000, HU, 117",
        "IE29AIBK93115212345678, IE, AIBK",
        "IS140159260076545510730339, IS, 0159",
        "IT60X0542811101000000123456, IT, 05428",
        "LI21088100002324013AA, LI, 08810",
        "LT121000011101001000, LT, 10000",
        "LU280019400644750000, LU, 001",
        "LV80BANK0000435195001, LV, BANK",
        "MC5811222000010123456789030, MC, 11222",
        "MT84MALT011000012345MTLCAST001S, MT, MALT",
        "NL91ABNA0417164300, NL, ABNA",
        "NO9386011117947, NO, 8601",
        "PL61109010140000071219812874, PL, 10901014",
        "PT50000201231234567890154, PT, 0002",
        "RO49AAAA1B31007593840000, RO, AAAA",
        "SE4550000000058398257466, SE, 500",
        "SI56191000000123438, SI, 19",
        "SK3112000000198742637541, SK, 1200",
        "SM86U0322509800000000270100, SM, 03225",
        "VA59001123000012345678, VA, 001",
    })
    void exposesCountryAndBankCode(String iban, String country, String bankCode) {
        Iban parsed = new Iban(iban);

        assertThat(parsed.countryCode()).isEqualTo(country);
        assertThat(parsed.bankCode()).isEqualTo(bankCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DE89370400440532013001", // checksum mismatch
        "DE8937040044053201300", // too short for DE
        "DE893704004405320130000", // too long for DE
        "TR330006100519786457841326", // valid IBAN, not a SEPA country
        "XX89370400440532013000", // unknown country
        "DE00370400440532013000", // check digits outside 02..98
        "DE01370400440532013000",
        "DE99370400440532013000",
        "DE8937040044053201300!", // not alphanumeric
        "DEAB370400440532013000", // check digits not numeric
        "D",
        "",
        "   ",
    })
    void rejectsInvalidIban(String iban) {
        assertRejected(() -> new Iban(iban), INVALID_IBAN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "GB29NWBK60161331926819",
        "gb29nwbk60161331926819",
    })
    void acceptsTheSameIbanInEitherCase(String iban) {
        assertThat(new Iban(iban).value()).isEqualTo("GB29NWBK60161331926819");
    }

    // Upper-casing "ß" gives "SS" and "ﬁ" gives "FI": a non-ASCII input must not become a valid-looking IBAN
    @ParameterizedTest
    @ValueSource(strings = {"GB80ßAB60161331926819", "ﬁ2112345600000785"})
    void rejectsNonAsciiLettersInsteadOfTransliterating(String iban) {
        // both would be valid after upper-casing
        assertThat(new Iban("GB80SSAB60161331926819").countryCode()).isEqualTo("GB");
        assertThat(new Iban("FI2112345600000785").countryCode()).isEqualTo("FI");

        assertRejected(() -> new Iban(iban), INVALID_IBAN);
    }

    @Test
    void rejectionMessageDoesNotContainTheIban() {
        assertThatThrownBy(() -> new Iban("DE89370400440532013001"))
                .message()
                .doesNotContain("370400440532013001");
    }

    @Test
    void maskedKeepsCountryCheckDigitsAndLastFour() {
        assertThat(new Iban("DE89370400440532013000").masked()).isEqualTo("DE89****3000");
    }

    @Test
    void toStringIsTheMaskedForm() {
        Iban iban = new Iban("DE89370400440532013000");

        assertThat(iban.toString()).isEqualTo("DE89****3000").doesNotContain("370400440532013000");
    }
}
