package com.tpe.paymentorchestrator;

import java.util.concurrent.atomic.AtomicLong;

/** Valid German IBANs of a bank code (deploy/config/bank-directory.yml), unique per JVM. */
final class Ibans {

    /** The own bank, {@code TPEBDEFFXXX}. */
    static final String OWN_BANK = "10010010";
    /** The peer bank, {@code PEERDEFFXXX}, reachable for SCT Inst. */
    static final String PEER_BANK = "20020020";
    /** Not in the bank directory. */
    static final String UNKNOWN_BANK = "30030030";

    private static final AtomicLong ACCOUNT_NUMBERS = new AtomicLong(1_000_000L);

    private Ibans() {}

    static String own() {
        return fresh(OWN_BANK);
    }

    static String peer() {
        return fresh(PEER_BANK);
    }

    /** {@code DE} + mod 97-10 check digits + bank code + a fresh 10-digit account number. */
    static String fresh(String bankCode) {
        String bban = bankCode + String.format("%010d", ACCOUNT_NUMBERS.incrementAndGet());
        // "DE00" moved behind the BBAN, letters as 10..35: D = 13, E = 14
        String digits = bban + "131400";
        int remainder = 0;
        for (int i = 0; i < digits.length(); i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return "DE" + String.format("%02d", 98 - remainder) + bban;
    }
}
