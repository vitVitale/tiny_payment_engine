package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.util.Objects;

/** The debtor or creditor of a payment, with the BIC of its bank. */
public record PaymentParty(PartyName name, Iban iban, Bic agentBic) {

    public PaymentParty {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(iban, "iban");
        Objects.requireNonNull(agentBic, "agentBic");
    }
}
