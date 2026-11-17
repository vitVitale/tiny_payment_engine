package com.tpe.paymentorchestrator.domain.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * How an investigation asks for a payment's status (specs/11-payment-orchestrator.md §6.4, §7, UC-05 4a, 4b): after
 * its n-th status request the next is due {@code backoff[n − 1]} later, the last value repeating; once it has run
 * longer than {@code budget} it gives up and an operator takes over.
 *
 * @param backoff {@code tpe.investigation.backoff}, each delay positive
 * @param budget {@code tpe.investigation.budget}, positive
 */
public record InvestigationPolicy(List<Duration> backoff, Duration budget) {

    public InvestigationPolicy {
        backoff = List.copyOf(Objects.requireNonNull(backoff, "backoff"));
        Objects.requireNonNull(budget, "budget");
        if (backoff.isEmpty() || backoff.stream().anyMatch(delay -> !delay.isPositive()) || !budget.isPositive()) {
            throw new IllegalArgumentException("investigation needs positive back-off delays (" + backoff
                    + ") and a positive budget (" + budget + ")");
        }
    }

    /**
     * @param requests the status requests of this investigation so far, at least 1
     * @return how long after the last of them the next is due
     */
    public Duration delayAfter(int requests) {
        if (requests < 1) {
            throw new IllegalArgumentException("no delay before the first request: " + requests);
        }
        return backoff.get(Math.min(requests, backoff.size()) - 1);
    }

    /** The investigation started at {@code startedAt} has run longer than the budget at {@code now}. */
    public boolean exhausted(Instant startedAt, Instant now) {
        return Duration.between(startedAt, now).compareTo(budget) > 0;
    }
}
