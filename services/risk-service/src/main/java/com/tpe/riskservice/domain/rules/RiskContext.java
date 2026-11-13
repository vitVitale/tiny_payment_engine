package com.tpe.riskservice.domain.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The payer's history for one assessment (specs/14-risk-service.md §6.1): the {@code ALLOW} outgoing payments of the
 * debtor IBAN in the {@code lookback} before {@code now}, and whether the debtor IBAN has had an {@code ALLOW}
 * outgoing payment to the creditor IBAN before. Read from {@code assessments} in one round trip before the rules
 * run; denied and reviewed payments never moved money, so they do not count. A window is
 * {@code (now − window, now]}.
 */
public final class RiskContext {

    /** No history was read: no rule that runs asks for it. */
    public static final RiskContext NOT_LOADED = new RiskContext(null, null, List.of(), false);

    private final Instant now;
    private final Duration lookback;
    private final List<PastPayment> allowedOutgoing;
    private final boolean payeePaidBefore;

    private RiskContext(Instant now, Duration lookback, List<PastPayment> allowedOutgoing, boolean payeePaidBefore) {
        this.now = now;
        this.lookback = lookback;
        this.allowedOutgoing = List.copyOf(allowedOutgoing);
        this.payeePaidBefore = payeePaidBefore;
    }

    /** {@code allowedOutgoing} holds (at least) the payer's allowed payments in {@code (now − lookback, now]}. */
    public static RiskContext loaded(Instant now, Duration lookback, List<PastPayment> allowedOutgoing,
            boolean payeePaidBefore) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lookback, "lookback");
        return new RiskContext(now, lookback, allowedOutgoing, payeePaidBefore);
    }

    /** The payer's allowed outgoing payments in {@code window}. */
    public long count(Duration window) {
        return within(window).count();
    }

    /** The amount in minor units of the payer's allowed outgoing payments in {@code window}. */
    public long amountMinor(Duration window) {
        return within(window).mapToLong(PastPayment::amountMinor).sum();
    }

    /** Whether the payer has had an allowed outgoing payment to this creditor IBAN, at any time. */
    public boolean payeePaidBefore() {
        requireLoaded();
        return payeePaidBefore;
    }

    private Stream<PastPayment> within(Duration window) {
        requireLoaded();
        if (window.compareTo(lookback) > 0) {
            throw new IllegalStateException("window " + window + " is beyond the loaded look-back " + lookback);
        }
        Instant since = now.minus(window);
        return allowedOutgoing.stream().filter(p -> p.assessedAt().isAfter(since) && !p.assessedAt().isAfter(now));
    }

    private void requireLoaded() {
        if (now == null) {
            throw new IllegalStateException("no history was loaded: the rule declares no look-back");
        }
    }

    @Override
    public String toString() {
        return now == null ? "RiskContext[NOT_LOADED]"
                : "RiskContext[now=" + now + ", lookback=" + lookback + ", allowedOutgoing=" + allowedOutgoing.size()
                        + ", payeePaidBefore=" + payeePaidBefore + "]";
    }
}
