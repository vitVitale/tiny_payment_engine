package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_BANK_DIRECTORY;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One bank of the {@link BankDirectory}: its national bank code (as inside its IBANs), BIC, whether it is
 * reachable for SCT Inst, and its VoP endpoint, empty for the own bank (answered locally).
 */
public record BankEntry(String bankCode, Bic bic, boolean sctInstReachable, Optional<URI> vopEndpoint) {

    public BankEntry {
        Objects.requireNonNull(bankCode, "bankCode");
        Objects.requireNonNull(bic, "bic");
        Objects.requireNonNull(vopEndpoint, "vopEndpoint");
        if (bankCode.isBlank()) {
            throw new DomainValidationException(INVALID_BANK_DIRECTORY, "bank code is empty");
        }
    }
}
