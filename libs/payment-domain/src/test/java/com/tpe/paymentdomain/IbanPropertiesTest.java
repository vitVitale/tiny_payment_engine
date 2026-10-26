package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_IBAN;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class IbanPropertiesTest {

    // IBAN lengths of the SEPA countries, from the SWIFT IBAN registry
    private static final Map<String, Integer> LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AT", 20), Map.entry("BE", 16), Map.entry("BG", 22),
            Map.entry("CH", 21), Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22),
            Map.entry("DK", 18), Map.entry("EE", 20), Map.entry("ES", 24), Map.entry("FI", 18),
            Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GI", 23), Map.entry("GR", 27),
            Map.entry("HR", 21), Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IS", 26),
            Map.entry("IT", 27), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20),
            Map.entry("LV", 21), Map.entry("MC", 27), Map.entry("MT", 31), Map.entry("NL", 18),
            Map.entry("NO", 15), Map.entry("PL", 28), Map.entry("PT", 25), Map.entry("RO", 24),
            Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27),
            Map.entry("VA", 22));

    @Property
    void validIbansRoundTrip(@ForAll("validIbans") String iban) {
        assertThat(new Iban(iban).value()).isEqualTo(iban);
    }

    @Property
    void printedFormRoundTrips(@ForAll("validIbans") String iban) {
        String printed = iban.toLowerCase(Locale.ROOT).replaceAll("(.{4})", "$1 ").trim();

        assertThat(new Iban(printed)).isEqualTo(new Iban(iban));
    }

    @Property
    void changingOneDigitFailsTheCheck(
            @ForAll("validIbans") String iban, @ForAll @IntRange(min = 2, max = 30) int position, @ForAll @IntRange(min = 1, max = 9) int delta) {
        int index = position % iban.length();
        char original = iban.charAt(index);
        if (index < 2 || !Character.isDigit(original)) {
            return;
        }
        char changed = (char) ('0' + (original - '0' + delta) % 10);
        String altered = iban.substring(0, index) + changed + iban.substring(index + 1);

        assertRejected(() -> new Iban(altered), INVALID_IBAN);
    }

    @Provide
    Arbitrary<String> validIbans() {
        return Arbitraries.of(LENGTHS.keySet()).flatMap(country -> Combinators.combine(
                        Arbitraries.strings().withCharRange('0', '9').ofLength(LENGTHS.get(country) - 4),
                        Arbitraries.strings().withCharRange('0', '9').withCharRange('A', 'Z').ofLength(LENGTHS.get(country) - 4),
                        Arbitraries.of(true, false))
                .as((digits, alnum, digitsOnly) -> withCheckDigits(country, digitsOnly ? digits : alnum)));
    }

    private static String withCheckDigits(String country, String bban) {
        StringBuilder numeric = new StringBuilder();
        for (char c : (bban + country + "00").toCharArray()) {
            numeric.append(Character.getNumericValue(c));
        }
        int check = 98 - new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
        return country + String.format(Locale.ROOT, "%02d", check) + bban;
    }
}
