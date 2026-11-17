package com.tpe.paymentorchestrator.application.cases;

import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentorchestrator.application.csm.CsmEventHandling;
import com.tpe.paymentorchestrator.application.payments.LockedPayment;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.application.timers.InvestigationTimer;
import com.tpe.paymentorchestrator.domain.payment.CaseResolution;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCase;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * An operator resolves an exception case (specs/11-payment-orchestrator.md §3.2), in one transaction that locks the
 * case and its payment: the resolution's effect, a {@code payment_transitions} row with the operator's {@code sub}
 * as {@code actor} and cause {@code OPERATOR_<resolution>} (the state may stay), and the case {@code RESOLVED}. An
 * action it makes due runs after the commit.
 *
 * <ul>
 *   <li>{@code RETRY_INVESTIGATION}: the investigation restarts now, with a status request of the next attempt;
 *   <li>{@code CONFIRMED_SETTLED} / {@code CONFIRMED_REJECTED}: as if the CSM's event arrived: the hold is settled /
 *       released;
 *   <li>{@code RETRY_NOW}: the stuck action is due at once;
 *   <li>{@code ACKNOWLEDGED}: nothing else.
 * </ul>
 *
 * The resolutions of an unresolved investigation act on a payment still {@code INVESTIGATING} without a CSM outcome.
 * Once the CSM answered after all, a confirmation that agrees with its answer closes the case and changes nothing
 * else; a retry or a disagreeing confirmation is refused as outdated.
 */
public class CaseResolutions {

    /** {@code payment_transitions.cause} of a resolution: {@code OPERATOR_} and its name. */
    static final String CAUSE_PREFIX = "OPERATOR_";

    private static final Logger LOG = LoggerFactory.getLogger(CaseResolutions.class);

    private final ExceptionCases cases;
    private final Payments payments;
    private final InvestigationTimer investigation;
    private final CsmEventHandling csmEvents;
    private final SagaRunner runner;
    private final Clock clock;

    public CaseResolutions(ExceptionCases cases, Payments payments, InvestigationTimer investigation,
            CsmEventHandling csmEvents, SagaRunner runner, Clock clock) {
        this.cases = cases;
        this.payments = payments;
        this.investigation = investigation;
        this.csmEvents = csmEvents;
        this.runner = runner;
        this.clock = clock;
    }

    /**
     * @param reason the operator's reason; required for {@code CONFIRMED_REJECTED}, ignored otherwise
     * @param comment may be {@code null}
     * @return the resolved case
     * @throws CaseNotFoundException no case with this id
     * @throws CaseAlreadyResolvedException the case is {@code RESOLVED}
     * @throws ResolutionNotAllowedException {@code resolution} does not resolve the case's type
     * @throws CaseOutdatedException the payment moved on since the case opened
     */
    @Transactional
    public ExceptionCase resolve(UUID caseId, CaseResolution resolution, StatusReason reason, String comment,
            String operator) {
        Objects.requireNonNull(operator, "operator");
        // the payment before its case, the order the listener and the timers lock in (§6.6)
        LockedPayment locked = cases.find(caseId).map(ExceptionCase::paymentId)
                .flatMap(payments::lockForTimer).orElse(null);
        ExceptionCase found = cases.lock(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        if (found.status() == ExceptionCase.Status.RESOLVED) {
            throw new CaseAlreadyResolvedException(caseId);
        }
        if (!resolution.resolves(found.type())) {
            throw new ResolutionNotAllowedException(found.type(), resolution);
        }
        if (resolution.needsReasonCode()) {
            Objects.requireNonNull(reason, "reason");
        }
        Instant now = clock.instant();
        if (locked != null) {
            apply(found, locked, resolution, reason, operator, now);
        }
        cases.resolve(caseId, resolution, operator, comment, now);
        // the operator is in the case and the transition, not in the log
        LOG.info("case {} {} resolved {}", caseId, found.type(), resolution);
        return cases.find(caseId).orElseThrow();
    }

    private void apply(ExceptionCase found, LockedPayment locked, CaseResolution resolution, StatusReason reason,
            String operator, Instant now) {
        Payment payment = locked.payment();
        String cause = CAUSE_PREFIX + resolution.name();
        switch (resolution) {
            case RETRY_INVESTIGATION -> {
                requireInvestigating(found, locked);
                record(payment, cause, operator, now);
                investigation.restart(payment, now);
            }
            case CONFIRMED_SETTLED -> confirm(found, locked, new OutgoingCsmEvent.Settled(), cause, operator, now);
            case CONFIRMED_REJECTED ->
                    confirm(found, locked, new OutgoingCsmEvent.Rejected(reason), cause, operator, now);
            case RETRY_NOW -> {
                record(payment, cause, operator, now);
                // only the action the case is about: one that has moved on is not stuck any more
                PendingAction stuck = stuckAction(found);
                if (stuck != null && payments.retryActionNow(payment.paymentId(), stuck, now)) {
                    runner.submitAfterCommit(payment.paymentId(), false);
                }
            }
            case ACKNOWLEDGED -> record(payment, cause, operator, now);
        }
    }

    /**
     * {@code CONFIRMED_SETTLED} / {@code CONFIRMED_REJECTED}: applied as the CSM's event while the payment is still
     * investigating; once the CSM answered after all, the confirmation only closes the case if it agrees with that
     * answer.
     */
    private void confirm(ExceptionCase found, LockedPayment locked, OutgoingCsmEvent event, String cause,
            String operator, Instant now) {
        Payment payment = locked.payment();
        if (payment.state() == PaymentState.INVESTIGATING && !locked.csmOutcomeRecorded()) {
            csmEvents.applyConfirmed(locked, event, cause, operator, now);
            return;
        }
        // an outcome other than a rejection keeps the payment with the CSM until its hold is settled
        boolean csmSettled = locked.csmOutcomeRecorded() && payment.state() != PaymentState.REJECTED;
        boolean agrees = event instanceof OutgoingCsmEvent.Settled ? csmSettled
                : payment.state() == PaymentState.REJECTED && locked.csmOutcomeRecorded();
        if (!agrees) {
            throw new CaseOutdatedException(found.caseId(), payment.state());
        }
        record(payment, cause, operator, now);
    }

    private static PendingAction stuckAction(ExceptionCase found) {
        Object action = found.details().get("action");
        try {
            return action == null ? null : PendingAction.valueOf(action.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The decision as a transition within the payment's state. */
    private void record(Payment payment, String cause, String operator, Instant now) {
        payments.recordTransition(payment.paymentId(), payment.state(), payment.state(), payment.reasonCode(), cause,
                operator, now);
    }

    private static void requireInvestigating(ExceptionCase found, LockedPayment locked) {
        if (locked.payment().state() != PaymentState.INVESTIGATING || locked.csmOutcomeRecorded()) {
            throw new CaseOutdatedException(found.caseId(), locked.payment().state());
        }
    }
}
