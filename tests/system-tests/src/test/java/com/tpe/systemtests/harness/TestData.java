package com.tpe.systemtests.harness;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unique test data per test (specs/20-testing-strategy.md §2): no two tests share a customer, so none asserts on
 * another's accounts. The ledger assigns the IBAN when the account is opened, so it is unique as well. Every name given
 * out and every IBAN {@link #rememberIban remembered} is personal data INV-14 scans the logs for ({@link #personalData}).
 */
public final class TestData {

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final Set<String> PERSONAL_DATA = ConcurrentHashMap.newKeySet();

    private TestData() {}

    /** A customer no other test uses: number {@code SYS-<uuid>} (the core system's format, 12 §3.2) and a name. */
    public static Customer newCustomer() {
        String name = "Test " + capitalised(word(8)) + " " + capitalised(word(10));
        PERSONAL_DATA.add(name);
        return new Customer("SYS-" + UUID.randomUUID(), name);
    }

    /** An IBAN the ledger gave a test's account: personal data from now on. */
    public static void rememberIban(String iban) {
        PERSONAL_DATA.add(iban);
    }

    /** The names given out and the IBANs remembered in this JVM so far. */
    public static Set<String> personalData() {
        return Set.copyOf(PERSONAL_DATA);
    }

    /**
     * A misspelling of {@code name} that vop-service answers {@code CLOSE_MATCH}: the name with letters of its last word
     * replaced from the end until its score lies in the middle of the band (13 §6.2: ≥ 0.85, < 0.95). The scenario
     * still asserts what vop-service answers; this only picks the input. Personal data from now on.
     */
    public static String closeMatchOf(String name) {
        char[] letters = name.toCharArray();
        for (int i = letters.length - 1; i > name.lastIndexOf(' '); i--) {
            letters[i] = letters[i] == 'x' ? 'y' : 'x';
            String misspelt = new String(letters);
            double score = nameScore(name, misspelt);
            if (score >= 0.87 && score <= 0.93) {
                PERSONAL_DATA.add(misspelt);
                return misspelt;
            }
        }
        throw new IllegalArgumentException("no close match for a name of " + name.length() + " characters");
    }

    /**
     * The name score of vop-service (13 §6.2) for the names the tests generate: lower-cased, whitespace collapsed,
     * punctuation a separator, Jaro-Winkler on the token-sorted and on the unsorted strings, the higher one. Leaves out
     * what generated names never have (diacritics, legal forms, titles).
     */
    static double nameScore(String holder, String input) {
        String a = normalised(holder);
        String b = normalised(input);
        return Math.max(jaroWinkler(a, b), jaroWinkler(sortedTokens(a), sortedTokens(b)));
    }

    /**
     * IBAN {@code DE.. 10010010 99........}: our bank code, a valid checksum, an account number the ledger never gives
     * (its sequence is far from 9.9 billion). Personal data from now on, as every IBAN a test uses.
     */
    public static String unknownOwnIban() {
        return germanIban("10010010" + "99");
    }

    /**
     * IBAN {@code DE.. 20020020 ..........} of the peer bank ({@code PEERDEFFXXX} in deploy/config/bank-directory.yml):
     * a valid checksum, a random account number. Personal data from now on.
     */
    public static String peerIban() {
        return germanIban("20020020" + "%02d".formatted(ThreadLocalRandom.current().nextInt(100)));
    }

    /** {@code DE}, the checksum, the bank code and account number prefix, and eight random digits. */
    private static String germanIban(String prefix) {
        String bban = prefix + "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
        int check = 98 - new BigInteger(bban + "131400").mod(BigInteger.valueOf(97)).intValue();
        String iban = "DE" + "%02d".formatted(check) + bban;
        PERSONAL_DATA.add(iban);
        return iban;
    }

    private static String normalised(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("\\p{Punct}", " ").trim().replaceAll("\\s+", " ");
    }

    private static String sortedTokens(String name) {
        return String.join(" ", Arrays.stream(name.split(" ")).sorted().toList());
    }

    /** Jaro-Winkler with the usual prefix scale 0.1 over at most 4 characters. */
    private static double jaroWinkler(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] matchedA = new boolean[a.length()];
        boolean[] matchedB = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int j = Math.max(0, i - window); j < Math.min(b.length(), i + window + 1); j++) {
                if (!matchedB[j] && a.charAt(i) == b.charAt(j)) {
                    matchedA[i] = true;
                    matchedB[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        for (int i = 0, j = 0; i < a.length(); i++) {
            if (matchedA[i]) {
                while (!matchedB[j]) {
                    j++;
                }
                if (a.charAt(i) != b.charAt(j)) {
                    transpositions++;
                }
                j++;
            }
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(a.length(), b.length())) && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    /** A bank customer: the core system's customer number and the holder's name (SEPA character set, ≤ 70). */
    public record Customer(String customerId, String name) {}

    private static String word(int length) {
        var random = ThreadLocalRandom.current();
        var word = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            word.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        }
        return word.toString();
    }

    private static String capitalised(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
