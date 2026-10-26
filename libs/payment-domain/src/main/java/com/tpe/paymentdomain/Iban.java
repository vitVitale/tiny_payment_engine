package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_IBAN;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An IBAN of a SEPA country in electronic format (upper case, no spaces), validated by length and the ISO 7064
 * mod 97-10 check. {@link #toString()} is masked: IBANs are personal data (specs/26-observability.md §4.4).
 */
public record Iban(String value) {

    /** Checked before upper-casing, which would turn e.g. "ß" into "SS" (no silent transliteration). */
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]+");

    /** IBAN length and bank identifier position within the BBAN (start inclusive, end exclusive), per the SWIFT IBAN registry. */
    private record CountryFormat(int length, int bankStart, int bankEnd) {}

    private static final Map<String, CountryFormat> SEPA_COUNTRIES = Map.ofEntries(
            Map.entry("AD", new CountryFormat(24, 0, 4)),
            Map.entry("AT", new CountryFormat(20, 0, 5)),
            Map.entry("BE", new CountryFormat(16, 0, 3)),
            Map.entry("BG", new CountryFormat(22, 0, 4)),
            Map.entry("CH", new CountryFormat(21, 0, 5)),
            Map.entry("CY", new CountryFormat(28, 0, 3)),
            Map.entry("CZ", new CountryFormat(24, 0, 4)),
            Map.entry("DE", new CountryFormat(22, 0, 8)),
            Map.entry("DK", new CountryFormat(18, 0, 4)),
            Map.entry("EE", new CountryFormat(20, 0, 2)),
            Map.entry("ES", new CountryFormat(24, 0, 4)),
            Map.entry("FI", new CountryFormat(18, 0, 3)),
            Map.entry("FR", new CountryFormat(27, 0, 5)),
            Map.entry("GB", new CountryFormat(22, 0, 4)),
            Map.entry("GI", new CountryFormat(23, 0, 4)),
            Map.entry("GR", new CountryFormat(27, 0, 3)),
            Map.entry("HR", new CountryFormat(21, 0, 7)),
            Map.entry("HU", new CountryFormat(28, 0, 3)),
            Map.entry("IE", new CountryFormat(22, 0, 4)),
            Map.entry("IS", new CountryFormat(26, 0, 4)),
            Map.entry("IT", new CountryFormat(27, 1, 6)),
            Map.entry("LI", new CountryFormat(21, 0, 5)),
            Map.entry("LT", new CountryFormat(20, 0, 5)),
            Map.entry("LU", new CountryFormat(20, 0, 3)),
            Map.entry("LV", new CountryFormat(21, 0, 4)),
            Map.entry("MC", new CountryFormat(27, 0, 5)),
            Map.entry("MT", new CountryFormat(31, 0, 4)),
            Map.entry("NL", new CountryFormat(18, 0, 4)),
            Map.entry("NO", new CountryFormat(15, 0, 4)),
            Map.entry("PL", new CountryFormat(28, 0, 8)),
            Map.entry("PT", new CountryFormat(25, 0, 4)),
            Map.entry("RO", new CountryFormat(24, 0, 4)),
            Map.entry("SE", new CountryFormat(24, 0, 3)),
            Map.entry("SI", new CountryFormat(19, 0, 2)),
            Map.entry("SK", new CountryFormat(24, 0, 4)),
            Map.entry("SM", new CountryFormat(27, 1, 6)),
            Map.entry("VA", new CountryFormat(22, 0, 3)));

    public Iban {
        Objects.requireNonNull(value, "value");
        value = value.replace(" ", "");
        if (!SHAPE.matcher(value).matches()) {
            throw new DomainValidationException(INVALID_IBAN, "not an IBAN");
        }
        value = value.toUpperCase(Locale.ROOT);
        CountryFormat format = SEPA_COUNTRIES.get(value.substring(0, 2));
        if (format == null) {
            throw new DomainValidationException(INVALID_IBAN, "country is not in SEPA");
        }
        if (value.length() != format.length()) {
            throw new DomainValidationException(INVALID_IBAN, "wrong length for country");
        }
        int checkDigits = Integer.parseInt(value.substring(2, 4));
        if (checkDigits < 2 || checkDigits > 98 || mod97(value) != 1) {
            throw new DomainValidationException(INVALID_IBAN, "checksum mismatch");
        }
    }

    /** ISO 7064 mod 97-10 over the IBAN with its first four characters moved to the end, letters as 10..35. */
    private static int mod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            int digit = Character.digit(rearranged.charAt(i), 36);
            remainder = (digit < 10 ? remainder * 10 : remainder * 100) + digit;
            remainder %= 97;
        }
        return remainder;
    }

    public String countryCode() {
        return value.substring(0, 2);
    }

    /** The national bank identifier inside the BBAN, used for routing (specs/00-engineering-baseline.md §4). */
    public String bankCode() {
        CountryFormat format = SEPA_COUNTRIES.get(countryCode());
        return value.substring(4 + format.bankStart(), 4 + format.bankEnd());
    }

    /** Country, check digits and the last four characters, e.g. {@code DE89****3000}. */
    public String masked() {
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    @Override
    public String toString() {
        return masked();
    }
}
