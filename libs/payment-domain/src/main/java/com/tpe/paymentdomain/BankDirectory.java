package com.tpe.paymentdomain;

import java.util.Optional;

/**
 * Static routing directory of the banks we know (specs/01-shared-libraries.md §1.4): reachability, on-us detection,
 * creditor agent BIC, VoP routing and the participants allowed to ask our VoP responder. A bank matches an IBAN by the bank code inside it and by country: bank codes
 * are national, so the IBAN's country must equal the country of the bank's BIC.
 */
public interface BankDirectory {

    Optional<BankEntry> lookup(Iban iban);

    /**
     * The participant with this BIC, compared by institution ({@link Bic#bic8()}): a requesting VoP participant may
     * send {@code PEERDEFF} for the directory's {@code PEERDEFFXXX}.
     */
    Optional<BankEntry> lookup(Bic bic);

    /** True only for IBANs of the own bank. */
    boolean isOwn(Iban iban);

    Bic ownBic();
}
