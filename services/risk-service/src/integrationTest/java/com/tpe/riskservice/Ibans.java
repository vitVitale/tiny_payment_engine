package com.tpe.riskservice;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Valid IBANs of the own bank ({@code 10010010}, deploy/config/bank-directory.yml), unique per JVM: a payer of its
 * own keeps one test's payments out of another's velocity aggregates.
 */
final class Ibans {

    private static final AtomicLong ACCOUNT_NUMBERS = new AtomicLong(1_000_000L);

    private Ibans() {}

    /** {@code DE} + mod 97-10 check digits + bank code + a fresh 10-digit account number. */
    static String fresh() {
        String bban = "10010010" + String.format("%010d", ACCOUNT_NUMBERS.incrementAndGet());
        // "DE00" moved behind the BBAN, letters as 10..35: D = 13, E = 14
        String digits = bban + "131400";
        int remainder = 0;
        for (int i = 0; i < digits.length(); i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return "DE" + String.format("%02d", 98 - remainder) + bban;
    }
}
