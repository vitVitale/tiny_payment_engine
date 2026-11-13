package com.tpe.riskservice.domain.rules;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import java.util.Objects;

/**
 * What the rules see of a payment (specs/14-risk-service.md §3.1, §6.1). {@link #toString()} leaves out names and
 * IBANs, which are personal data (specs/26-observability.md §4.4).
 *
 * @param payeeVerification set for {@code OUTGOING}, {@code null} for {@code INCOMING}
 */
public record PaymentToAssess(
        PaymentDirection direction,
        Money amount,
        PartyName debtorName,
        Iban debtorIban,
        PartyName creditorName,
        Iban creditorIban,
        PayeeVerification payeeVerification) {

    public PaymentToAssess {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(debtorName, "debtorName");
        Objects.requireNonNull(debtorIban, "debtorIban");
        Objects.requireNonNull(creditorName, "creditorName");
        Objects.requireNonNull(creditorIban, "creditorIban");
        if ((direction == PaymentDirection.OUTGOING) != (payeeVerification != null)) {
            throw new IllegalArgumentException("an OUTGOING payment, and only one, carries the VoP result");
        }
    }

    @Override
    public String toString() {
        return "PaymentToAssess[direction=" + direction + ", amount=" + amount + "]";
    }
}
