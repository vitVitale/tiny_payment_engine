package com.tpe.observability.test;

import com.tpe.paymentdomain.Iban;
import java.util.List;
import java.util.Locale;

/**
 * Personal data that must never reach a log line at INFO or above (specs/00-engineering-baseline.md §11,
 * specs/26-observability.md §4.4): tests use these names and IBANs as parties, then scan the captured logs for them.
 *
 * @param names party names, matched case-insensitively
 * @param ibans IBANs, matched electronically and grouped in fours; the masked form is allowed
 */
public record PiiCanaries(List<String> names, List<Iban> ibans) {

    /** Valid IBANs, so they pass value-object validation on their way through the code under test. */
    public static final PiiCanaries DEFAULT = new PiiCanaries(
            List.of("Ottilie Canarywood", "Bartholomew Finchley-Canary"),
            List.of(new Iban("DE53999999990000000042"), new Iban("NL41CANA0000000042")));

    public PiiCanaries {
        names = List.copyOf(names);
        ibans = List.copyOf(ibans);
    }

    /** Whether {@code text} contains a canary. */
    public boolean foundIn(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return names.stream().anyMatch(name -> lower.contains(name.toLowerCase(Locale.ROOT)))
                || ibans.stream().anyMatch(iban -> text.contains(iban.value()) || text.contains(grouped(iban.value())));
    }

    /** {@code iban} in groups of four, as printed on paper: {@code DE53 9999 …}. */
    static String grouped(String iban) {
        return iban.replaceAll("(.{4})(?!$)", "$1 ");
    }
}
