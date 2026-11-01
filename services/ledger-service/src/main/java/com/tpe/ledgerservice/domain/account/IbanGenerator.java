package com.tpe.ledgerservice.domain.account;

import com.tpe.paymentdomain.Iban;
import java.util.regex.Pattern;

/**
 * IBANs of our customer accounts (specs/12-ledger-service.md §6.4): {@code DE} + mod 97-10 check digits + our 8-digit
 * bank code + the account number zero-padded to 10 digits.
 */
public final class IbanGenerator {

    public static final long MAX_ACCOUNT_NUMBER = 9_999_999_999L;

    private static final Pattern BANK_CODE = Pattern.compile("[0-9]{8}");
    /** "DE00" moved behind the BBAN, letters as 10..35: D = 13, E = 14. */
    private static final String COUNTRY_SUFFIX = "131400";

    private final String bankCode;

    /** @param bankCode {@code tpe.bank.bank-code}, 8 digits */
    public IbanGenerator(String bankCode) {
        if (bankCode == null || !BANK_CODE.matcher(bankCode).matches()) {
            throw new IllegalArgumentException("tpe.bank.bank-code must be 8 digits");
        }
        this.bankCode = bankCode;
    }

    /** @param accountNumber the next value of {@code account_number_seq}, 1 … {@link #MAX_ACCOUNT_NUMBER} */
    public Iban generate(long accountNumber) {
        if (accountNumber < 1) {
            throw new IllegalArgumentException("account numbers start at 1");
        }
        if (accountNumber > MAX_ACCOUNT_NUMBER) {
            throw new IllegalStateException("account numbers are exhausted: more than 10 digits");
        }
        String bban = bankCode + String.format("%010d", accountNumber);
        int checkDigits = 98 - mod97(bban + COUNTRY_SUFFIX);
        return new Iban("DE" + String.format("%02d", checkDigits) + bban);
    }

    private static int mod97(String digits) {
        int remainder = 0;
        for (int i = 0; i < digits.length(); i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return remainder;
    }
}
