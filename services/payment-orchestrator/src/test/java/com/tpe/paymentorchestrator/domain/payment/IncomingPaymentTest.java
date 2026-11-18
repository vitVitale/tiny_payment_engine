package com.tpe.paymentorchestrator.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.paymentdomain.ReasonCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The moves of an incoming payment up to its answer (specs/11-payment-orchestrator.md §6.2, §6.5, UC-06 2–7,
 * ADR-003): received, risk, pending credit, verdict; the state follows the answer the gateway sent.
 */
class IncomingPaymentTest {

    static final Instant RECEIVED_AT = IncomingPayments.RECEIVED_AT;

    @Test
    void aReceivedTransferIsAssessedFirstWithItsAnswerDeadline() {
        Payment received = IncomingPayments.received();

        assertThat(received.direction()).isEqualTo(PaymentDirection.INCOMING);
        assertThat(received.state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(received.pendingAction()).isEqualTo(PendingAction.ASSESS_RISK);
        assertThat(received.acceptedAt()).isEqualTo(RECEIVED_AT);
        assertThat(received.incoming().answerDeadline()).isEqualTo(RECEIVED_AT.plusSeconds(5));
        assertThat(received.incoming().verdict()).isNull();
        assertThat(received.sendBy()).isNull();
        assertThat(received.deadlineAt()).isNull();
    }

    @Test
    void anAllowedTransferGetsItsPendingCreditNextAndStaysReceived() {
        Payment checked = IncomingPayments.received().incomingRiskChecked();

        assertThat(checked.state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(checked.pendingAction()).isEqualTo(PendingAction.CREATE_PENDING_CREDIT);
    }

    @Test
    void aPendingCreditMakesTheAcceptVerdict() {
        UUID payee = UUID.randomUUID();

        Payment requested = IncomingPayments.received().incomingRiskChecked().acceptRequested(payee);

        assertThat(requested.state()).isEqualTo(PaymentState.ACCEPT_REQUESTED);
        assertThat(requested.pendingAction()).isNull();
        assertThat(requested.creditorAccountId()).isEqualTo(payee);
        assertThat(requested.incoming().verdict()).isEqualTo(Verdict.ACCEPT);
        assertThat(requested.incoming().verdictReason()).isNull();
    }

    @Test
    void aRejectVerdictKeepsTheTransferReceivedUntilTheAnswerIsSent() {
        Payment rejected = IncomingPayments.received().verdictRejected(ReasonCode.FR01);

        assertThat(rejected.state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(rejected.reasonCode()).isNull();
        assertThat(rejected.pendingAction()).isNull();
        assertThat(rejected.finalisedAt()).isNull();
        assertThat(rejected.incoming().verdict()).isEqualTo(Verdict.REJECT);
        assertThat(rejected.incoming().verdictReason()).isEqualTo(ReasonCode.FR01);
    }

    @Test
    void onlyAReceivedTransferWithoutVerdictGetsOne() {
        Payment rejected = IncomingPayments.received().verdictRejected(ReasonCode.FR01);

        assertThatThrownBy(() -> rejected.verdictRejected(ReasonCode.MS03)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IncomingPayments.outgoing().verdictRejected(ReasonCode.FR01))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAcceptingAnswerAcceptsTheRequestedTransfer() {
        Payment requested = IncomingPayments.acceptRequested();

        var decision = (AnswerDecision.Moved) AnswerDecision.decide(requested, AnswerDecision.Answer.ACCP, null,
                RECEIVED_AT.plusSeconds(1));

        assertThat(decision.next().state()).isEqualTo(PaymentState.ACCEPTED);
        assertThat(decision.next().pendingAction()).isNull();
        assertThat(decision.cause()).isEqualTo("ANSWER_ACCP");
    }

    @Test
    void anAcceptingAnswerForATransferWeNeverAcceptedIsAContradiction() {
        Payment rejected = IncomingPayments.received().verdictRejected(ReasonCode.FR01);

        assertThat(AnswerDecision.decide(rejected, AnswerDecision.Answer.ACCP, null, RECEIVED_AT))
                .isInstanceOf(AnswerDecision.Contradiction.class);
        assertThat(AnswerDecision.decide(IncomingPayments.received(), AnswerDecision.Answer.ACCP, null, RECEIVED_AT))
                .isInstanceOf(AnswerDecision.Contradiction.class);
    }

    @Test
    void aRejectingAnswerRejectsWithTheSentReasonAndCancelsTheCredit() {
        Instant at = RECEIVED_AT.plusSeconds(5);
        for (Payment payment : new Payment[] {IncomingPayments.received().verdictRejected(ReasonCode.FR01),
                IncomingPayments.received().incomingRiskChecked(), IncomingPayments.acceptRequested()}) {
            var decision = (AnswerDecision.Moved) AnswerDecision.decide(payment, AnswerDecision.Answer.RJCT,
                    ReasonCode.AB05, at);

            assertThat(decision.next().state()).as("from %s", payment.state()).isEqualTo(PaymentState.REJECTED);
            assertThat(decision.next().reasonCode()).isEqualTo(ReasonCode.AB05);
            assertThat(decision.next().pendingAction()).isEqualTo(PendingAction.CANCEL_CREDIT);
            assertThat(decision.next().finalisedAt()).isEqualTo(at);
            assertThat(decision.cause()).isEqualTo("ANSWER_RJCT");
        }
    }

    @Test
    void aRejectingAnswerForARejectedTransferChangesNothing() {
        Payment rejected = IncomingPayments.received().rejected(ReasonCode.FR01, PendingAction.CANCEL_CREDIT,
                RECEIVED_AT);

        assertThat(AnswerDecision.decide(rejected, AnswerDecision.Answer.RJCT, ReasonCode.FR01, RECEIVED_AT))
                .isInstanceOf(AnswerDecision.NoOp.class);
    }

    @Test
    void aRejectingAnswerForAnAcceptedTransferIsAContradiction() {
        Payment accepted = ((AnswerDecision.Moved) AnswerDecision.decide(IncomingPayments.acceptRequested(),
                AnswerDecision.Answer.ACCP, null, RECEIVED_AT)).next();

        assertThat(AnswerDecision.decide(accepted, AnswerDecision.Answer.RJCT, ReasonCode.AB05, RECEIVED_AT))
                .isInstanceOf(AnswerDecision.Contradiction.class);
    }

    @Test
    void theCancelledCreditCompensatesTheRejection() {
        Payment rejected = IncomingPayments.acceptRequested().rejected(ReasonCode.AB05, PendingAction.CANCEL_CREDIT,
                RECEIVED_AT);

        Payment compensated = rejected.compensated();

        assertThat(compensated.state()).isEqualTo(PaymentState.REJECTED);
        assertThat(compensated.pendingAction()).isNull();
        assertThat(compensated.incoming()).isEqualTo(rejected.incoming());
    }
}
