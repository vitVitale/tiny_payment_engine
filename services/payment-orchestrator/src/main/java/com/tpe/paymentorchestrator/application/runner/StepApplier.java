package com.tpe.paymentorchestrator.application.runner;

import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentorchestrator.application.payments.ActionClaim;
import com.tpe.paymentorchestrator.application.payments.FinalEvents;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second transaction of a step (specs/11-payment-orchestrator.md §6.2, ADR-008): applies the outcome only while
 * the payment is still as claimed, records the transition if the state changed, opens the case the step names, and
 * writes the step's command for csm-gateway, and the final event when the payment became final with nothing left
 * to undo and no contradiction found.
 */
@Component
public class StepApplier {

    private static final Logger LOG = LoggerFactory.getLogger(StepApplier.class);

    /** {@code csm.outbound.commands}, key {@code txId} (specs/02-messaging-contracts.md §3). */
    public static final String CSM_COMMANDS = "csm.outbound.commands";

    private final Payments payments;
    private final FinalEvents finalEvents;
    private final OutboxWriter outbox;

    StepApplier(Payments payments, FinalEvents finalEvents, OutboxWriter outbox) {
        this.payments = payments;
        this.finalEvents = finalEvents;
        this.outbox = outbox;
    }

    /** @return {@code false} if the step was overtaken and its outcome discarded */
    @Transactional
    public boolean apply(ActionClaim claim, StepOutcome.Moved moved, Instant now) {
        Payment next = moved.next();
        boolean contradiction = moved.openCase() == ExceptionCaseType.CONTRADICTING_CSM_OUTCOME;
        boolean finalEvent = next.state().isFinal() && next.pendingAction() == null && !contradiction;
        if (!payments.applyStep(claim, next, finalEvent, now)) {
            LOG.info("payment {} {} result discarded: the payment moved on", claim.paymentId().value(),
                    claim.action());
            return false;
        }
        if (moved.openCase() != null) {
            payments.openCase(next.paymentId(), moved.openCase(), Map.of("action", claim.action().name(),
                    "cause", moved.cause()), now);
            LOG.warn("payment {} {}: {} case opened", next.paymentId().value(), moved.cause(), moved.openCase());
        }
        if (moved.command() != null) {
            // in the payment's trace: the runner's action span is current
            outbox.write(CSM_COMMANDS, next.txId().value(), moved.command());
        }
        if (finalEvent) {
            finalEvents.emit(next);
        }
        if (next.state() == claim.state()) {
            // a step within the state, e.g. the payee assessed: no transition
            return true;
        }
        payments.recordTransition(next.paymentId(), claim.state(), next.state(), next.reasonCode(), moved.cause(),
                now);
        // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
        LOG.info("payment {} txId {} {} -> {} ({}){}", next.paymentId().value(), next.txId().value(), claim.state(),
                next.state(), moved.cause(), next.reasonCode() == null ? "" : " " + next.reasonCode().code());
        return true;
    }

    /**
     * Schedules the claimed action again after a technical failure.
     *
     * @param stuck the failure of an action pending for {@code stuck-after}, which opens one
     *     {@code LEDGER_ACTION_STUCK} case; {@code null} if it is not stuck
     */
    @Transactional
    public void retry(ActionClaim claim, Instant at, Instant now, StepOutcome.TechnicalFailure stuck) {
        payments.retryAt(claim, at, now);
        if (stuck != null && payments.openCase(claim.paymentId(), ExceptionCaseType.LEDGER_ACTION_STUCK,
                Map.of("action", claim.action().name(), "attempts", claim.attempts(), "pendingSince",
                        claim.actionSince().toString(), "lastFailure", stuck.detail()), now)) {
            LOG.warn("payment {} {} stuck after {} attempts: LEDGER_ACTION_STUCK case opened",
                    claim.paymentId().value(), claim.action(), claim.attempts());
        }
    }
}
