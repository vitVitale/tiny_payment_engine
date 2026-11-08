package com.tpe.ledgerservice.application.reservation;

import com.tpe.ledgerservice.domain.reservation.IncomingReference;
import com.tpe.paymentdomain.Iban;
import java.util.Objects;
import java.util.UUID;

/**
 * Record an incoming payment on the payee account until it settles (specs/12-ledger-service.md §3.1).
 *
 * @param pendingCreditId the incoming payment's {@code paymentId} (ADR-010)
 * @param currency as the caller sent it; anything but EUR is refused
 * @param reference the payment and its {@code (debtorAgentBic, txId)}; its parts may be {@code null}
 */
public record CreatePendingCredit(UUID pendingCreditId, Iban creditorIban, long amountMinor, String currency,
        IncomingReference reference) {

    public CreatePendingCredit {
        Objects.requireNonNull(pendingCreditId, "pendingCreditId");
        Objects.requireNonNull(creditorIban, "creditorIban");
        Objects.requireNonNull(reference, "reference");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("a pending credit amount must be positive");
        }
    }
}
