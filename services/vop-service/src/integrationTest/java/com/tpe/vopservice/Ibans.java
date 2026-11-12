package com.tpe.vopservice;

import java.util.concurrent.atomic.AtomicLong;

/** Valid IBANs of the own bank ({@code 10010010}, deploy/config/bank-directory.yml), unique per JVM. */
final class Ibans {

    private static final AtomicLong ACCOUNT_NUMBERS = new AtomicLong(1_000_000L);

    private Ibans() {}

    /** {@code DE} + mod 97-10 check digits + bank code + a fresh 10-digit account number. */
    static String own() {
        return de("10010010", ACCOUNT_NUMBERS.incrementAndGet());
    }

    static String de(String bankCode, long accountNumber) {
        String bban = bankCode + String.format("%010d", accountNumber);
        // "DE00" moved behind the BBAN, letters as 10..35: D = 13, E = 14
        String digits = bban + "131400";
        int remainder = 0;
        for (int i = 0; i < digits.length(); i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return "DE" + String.format("%02d", 98 - remainder) + bban;
    }
}
