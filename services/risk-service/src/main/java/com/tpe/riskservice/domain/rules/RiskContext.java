package com.tpe.riskservice.domain.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The history of our customer's side of one payment (specs/14-risk-service.md §6.1): the customer's account is the
 * debtor IBAN of an {@code OUTGOING} payment and the creditor IBAN of an {@code INCOMING} one. Holds the {@code ALLOW}
 * payments of that account in the payment's direction in the {@code lookback} before {@code now}; for an outgoing
 * payment, whether the debtor IBAN has had an allowed payment to the creditor IBAN before; and when the account was
 * opened, from the holder projection (unknown if the projection has no such account). Read in one round trip before
 * the rules run; denied and reviewed payments never moved money, so they do not count. A window is
 * {@code (now − window, now]}.
 */
public final class RiskContext {

    /** No history was read: no rule that runs asks for it. */
    public static final RiskContext NOT_LOADED = new RiskContext(null, null, List.of(), false, null);

    private final Instant now;
    private final Duration lookback;
    private final List<PastPayment> allowedPayments;
    private final boolean payeePaidBefore;
    private final Instant accountOpenedAt;

    private RiskContext(Instant now, Duration lookback, List<PastPayment> allowedPayments, boolean payeePaidBefore,
            Instant accountOpenedAt) {
        this.now = now;
        this.lookback = lookback;
        this.allowedPayments = List.copyOf(allowedPayments);
        this.payeePaidBefore = payeePaidBefore;
        this.accountOpenedAt = accountOpenedAt;
    }

    /**
     * @param allowedPayments (at least) the customer account's allowed payments in {@code (now − lookback, now]}
     * @param accountOpenedAt {@code null} if the holder projection does not know the customer's account
     */
    public static RiskContext loaded(Instant now, Duration lookback, List<PastPayment> allowedPayments,
            boolean payeePaidBefore, Instant accountOpenedAt) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lookback, "lookback");
        return new RiskContext(now, lookback, allowedPayments, payeePaidBefore, accountOpenedAt);
    }

    /** The customer account's allowed payments in {@code window}, in the payment's direction. */
    public long count(Duration window) {
        return within(window).count();
    }

    /** The amount in minor units of the customer account's allowed payments in {@code window}. */
    public long amountMinor(Duration window) {
        return within(window).mapToLong(PastPayment::amountMinor).sum();
    }

    /** Whether the payer has had an allowed outgoing payment to this creditor IBAN, at any time. */
    public boolean payeePaidBefore() {
        requireLoaded();
        return payeePaidBefore;
    }

    /** How long ago the customer's account was opened; empty if the holder projection does not know it. */
    public Optional<Duration> accountAge() {
        requireLoaded();
        return Optional.ofNullable(accountOpenedAt).map(openedAt -> Duration.between(openedAt, now));
    }

    private Stream<PastPayment> within(Duration window) {
        requireLoaded();
        if (window.compareTo(lookback) > 0) {
            throw new IllegalStateException("window " + window + " is beyond the loaded look-back " + lookback);
        }
        Instant since = now.minus(window);
        return allowedPayments.stream().filter(p -> p.assessedAt().isAfter(since) && !p.assessedAt().isAfter(now));
    }

    private void requireLoaded() {
        if (now == null) {
            throw new IllegalStateException("no history was loaded: the rule declares no look-back");
        }
    }

    @Override
    public String toString() {
        return now == null ? "RiskContext[NOT_LOADED]"
                : "RiskContext[now=" + now + ", lookback=" + lookback + ", allowedPayments=" + allowedPayments.size()
                        + ", payeePaidBefore=" + payeePaidBefore + ", accountOpenedAt=" + accountOpenedAt + "]";
    }
}
