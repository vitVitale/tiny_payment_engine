package com.tpe.paymentorchestrator.application.runner;

import com.tpe.paymentorchestrator.application.payments.ActionClaim;
import com.tpe.paymentorchestrator.application.payments.FinalEvents;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second transaction of a step (specs/11-payment-orchestrator.md §6.2, ADR-008): applies the outcome only while
 * the payment is still as claimed, records the transition, and writes the final event when the payment became final
 * with nothing left to undo.
 */
@Component
public class StepApplier {

    private static final Logger LOG = LoggerFactory.getLogger(StepApplier.class);

    private final Payments payments;
    private final FinalEvents finalEvents;

    StepApplier(Payments payments, FinalEvents finalEvents) {
        this.payments = payments;
        this.finalEvents = finalEvents;
    }

    /** @return {@code false} if the step was overtaken and its outcome discarded */
    @Transactional
    public boolean apply(ActionClaim claim, StepOutcome.Moved moved, Instant now) {
        Payment next = moved.next();
        boolean finalEvent = next.state().isFinal() && next.pendingAction() == null;
        if (!payments.applyStep(claim, next, finalEvent, now)) {
            LOG.info("payment {} {} result discarded: the payment moved on", claim.paymentId().value(),
                    claim.action());
            return false;
        }
        payments.recordTransition(next.paymentId(), claim.state(), next.state(), next.reasonCode(), moved.cause(),
                now);
        if (finalEvent) {
            finalEvents.emit(next);
        }
        // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
        LOG.info("payment {} txId {} {} -> {} ({}){}", next.paymentId().value(), next.txId().value(), claim.state(),
                next.state(), moved.cause(), next.reasonCode() == null ? "" : " " + next.reasonCode().code());
        return true;
    }

    /** Schedules the claimed action again after a technical failure. */
    @Transactional
    public void retry(ActionClaim claim, Instant at, Instant now) {
        payments.retryAt(claim, at, now);
    }
}
