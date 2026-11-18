package com.tpe.paymentorchestrator.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.paymentorchestrator.domain.payment.IncomingPayments;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** One {@code pending_action} for both directions (specs/11-payment-orchestrator.md §6.2 {@code ASSESS_RISK}). */
class ByDirectionTest {

    private final ByDirection action = new ByDirection(named("outgoing", PendingAction.ASSESS_RISK),
            named("incoming", PendingAction.ASSESS_RISK));

    @Test
    void eachPaymentGoesToItsDirectionsAction() {
        assertThat(((StepOutcome.TechnicalFailure) action.perform(IncomingPayments.received())).detail())
                .isEqualTo("incoming");
        assertThat(((StepOutcome.TechnicalFailure) action.perform(IncomingPayments.outgoing())).detail())
                .isEqualTo("outgoing");
        assertThat(action.action()).isEqualTo(PendingAction.ASSESS_RISK);
    }

    @Test
    void bothMustBeTheSameAction() {
        assertThatThrownBy(() -> new ByDirection(named("outgoing", PendingAction.ASSESS_RISK),
                named("incoming", PendingAction.CREATE_PENDING_CREDIT))).isInstanceOf(IllegalArgumentException.class);
    }

    /** An action that fails with its name. */
    private static SagaAction named(String name, PendingAction pending) {
        return new SagaAction() {
            @Override
            public PendingAction action() {
                return pending;
            }

            @Override
            public StepOutcome perform(Payment payment) {
                return new StepOutcome.TechnicalFailure(name, false);
            }

            @Override
            public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts,
                    StepOutcome.TechnicalFailure failure) {
                return Optional.empty();
            }
        };
    }
}
