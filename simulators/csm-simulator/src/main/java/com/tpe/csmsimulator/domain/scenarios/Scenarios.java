package com.tpe.csmsimulator.domain.scenarios;

import com.tpe.csmsimulator.domain.scenarios.Scenario.Accept;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Reject;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Timeout;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.ReasonCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The scenario of each creditor IBAN (specs/17-csm-simulator.md §3.5). Without one, the cents of the amount pick a
 * scenario for manual testing ({@code .91} → {@code REJECT:AC01}, {@code .92} → {@code REJECT:AC04}, {@code .93} →
 * {@code TIMEOUT}); otherwise {@code ACCEPT}. Not thread-safe.
 */
public final class Scenarios {

    private final Map<Iban, Scenario> byCreditor = new HashMap<>();

    public void set(Iban creditor, Scenario scenario) {
        byCreditor.put(Objects.requireNonNull(creditor, "creditor"), Objects.requireNonNull(scenario, "scenario"));
    }

    public Scenario scenarioFor(Iban creditor, Money amount) {
        Scenario explicit = byCreditor.get(creditor);
        if (explicit != null) {
            return explicit;
        }
        return switch ((int) (amount.minor() % 100)) {
            case 91 -> new Reject(ReasonCode.AC01);
            case 92 -> new Reject(ReasonCode.AC04);
            case 93 -> new Timeout();
            default -> new Accept();
        };
    }

    public void clear() {
        byCreditor.clear();
    }
}
