package com.tpe.systemtests.harness;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unique test data per test (specs/20-testing-strategy.md §2): no two tests share a customer, so none asserts on
 * another's accounts. The ledger assigns the IBAN when the account is opened, so it is unique as well.
 */
public final class TestData {

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";

    private TestData() {}

    /** A customer no other test uses: number {@code SYS-<uuid>} (the core system's format, 12 §3.2) and a name. */
    public static Customer newCustomer() {
        return new Customer("SYS-" + UUID.randomUUID(), "Test " + capitalised(word(8)) + " " + capitalised(word(10)));
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
