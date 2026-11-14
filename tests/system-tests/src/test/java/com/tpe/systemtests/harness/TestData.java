package com.tpe.systemtests.harness;

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
