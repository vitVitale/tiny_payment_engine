package com.tpe.fixturesvc.domain;

import java.time.Clock;
import java.time.Instant;

public class CompliantPayment {

    private final long amountMinor;

    public CompliantPayment(long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public Instant acceptedAt(Clock clock) {
        return Instant.now(clock);
    }
}
