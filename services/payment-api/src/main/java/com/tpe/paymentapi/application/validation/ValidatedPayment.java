package com.tpe.paymentapi.application.validation;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;

/**
 * A {@link PaymentInstruction} that passed specs/10-payment-api.md §5.2: the values the reference check and the
 * {@code SubmitPayment} take.
 *
 * @param creditorBic as the channel gave it; {@code null} when it gave none
 * @param remittanceInformation {@code null} when the channel gave none
 * @param vopReference as the channel gave it, unchecked; {@code null} when it gave none
 */
public record ValidatedPayment(
        EndToEndId endToEndId,
        PartyName debtorName,
        Iban debtorIban,
        PartyName creditorName,
        Iban creditorIban,
        Bic creditorBic,
        Money amount,
        RemittanceInformation remittanceInformation,
        String vopReference) {

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "ValidatedPayment[amount=" + amount + ", vopReference=" + vopReference + "]";
    }
}
