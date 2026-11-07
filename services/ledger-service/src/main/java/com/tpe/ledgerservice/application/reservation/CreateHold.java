package com.tpe.ledgerservice.application.reservation;

import com.tpe.paymentdomain.Iban;
import java.util.Objects;
import java.util.UUID;

/**
 * Reserve money on the payer account (specs/12-ledger-service.md §3.1).
 *
 * @param currency as the caller sent it; anything but EUR is refused
 * @param paymentId the payment the hold is for, or {@code null}
 * @param txId its {@code TxId}, or {@code null}
 */
public record CreateHold(UUID holdId, Iban debtorIban, long amountMinor, String currency, UUID paymentId,
        String txId) {

    public CreateHold {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(debtorIban, "debtorIban");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("a hold amount must be positive");
        }
    }
}
