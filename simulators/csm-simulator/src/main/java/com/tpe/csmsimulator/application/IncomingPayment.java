package com.tpe.csmsimulator.application;

import com.tpe.csmsimulator.domain.scenarios.IncomingScenario;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.TxId;
import java.util.Objects;

/**
 * A credit transfer the CSM delivers to our bank ({@code POST /sim/v1/incoming-payments}, specs/17-csm-simulator.md
 * §3.2).
 *
 * @param txId {@code null} for a new one
 * @param debtorAgent {@code null} for the debtor IBAN's bank in the bank directory
 * @param scenario what the CSM does once we accept it in time
 */
public record IncomingPayment(Iban creditor, Money amount, PartyName debtorName, Iban debtorIban, TxId txId,
        Bic debtorAgent, IncomingScenario scenario) {

    public IncomingPayment {
        Objects.requireNonNull(creditor, "creditor");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(debtorName, "debtorName");
        Objects.requireNonNull(debtorIban, "debtorIban");
        Objects.requireNonNull(scenario, "scenario");
    }
}
