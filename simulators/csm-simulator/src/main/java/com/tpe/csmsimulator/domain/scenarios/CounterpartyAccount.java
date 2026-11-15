package com.tpe.csmsimulator.domain.scenarios;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.util.Objects;

/** An account at a simulated counterparty PSP (specs/17-csm-simulator.md §3.5), answered for by its VoP responder. */
public record CounterpartyAccount(Iban iban, PartyName holderName) {

    public CounterpartyAccount {
        Objects.requireNonNull(iban, "iban");
        Objects.requireNonNull(holderName, "holderName");
    }
}
