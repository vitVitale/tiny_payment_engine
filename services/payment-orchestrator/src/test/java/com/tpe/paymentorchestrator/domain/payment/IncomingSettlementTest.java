package com.tpe.paymentorchestrator.domain.payment;

import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.RECEIVED_AT;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.Apply;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.Contradiction;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.NoOp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The CSM's outcome of an accepted incoming payment (specs/11-payment-orchestrator.md §6.2, §6.5, UC-06 8a, 9): the
 * payee is credited when the CSM settles, the pending credit cancelled when it rejects; contradicting facts are cases.
 */
class IncomingSettlementTest {

    private static final Instant NOW = RECEIVED_AT.plusSeconds(3);
    private static final CsmReport SETTLED = new CsmReport.Settled();
    private static final CsmReport REJECTED_AB05 = new CsmReport.Rejected(ReasonCode.AB05);

    @Test
    void aSettlementOfAnAcceptedTransferConfirmsTheCreditNext() {
        for (Payment payment : new Payment[] {accepted(), IncomingPayments.acceptRequested()}) {
            var apply = (Apply) CsmEventDecision.decide(payment, false, SETTLED, NOW);

            assertThat(apply.next().state()).as("from %s", payment.state()).isEqualTo(payment.state());
            assertThat(apply.next().pendingAction()).isEqualTo(PendingAction.CONFIRM_CREDIT);
            assertThat(apply.outcome()).isEqualTo(CsmOutcome.SETTLED);
            assertThat(apply.cause()).isEqualTo("CSM_SETTLED");
        }
    }

    @Test
    void aSettlementOnceRecordedIsANoOp() {
        Payment confirming = ((Apply) CsmEventDecision.decide(accepted(), false, SETTLED, NOW)).next();

        assertThat(CsmEventDecision.decide(confirming, true, SETTLED, NOW)).isInstanceOf(NoOp.class);
        assertThat(CsmEventDecision.decide(settled(), true, SETTLED, NOW)).isInstanceOf(NoOp.class);
    }

    @Test
    void aSettlementOfARejectedTransferIsAContradiction() {
        Payment rejected = accepted().rejected(ReasonCode.AB05, PendingAction.CANCEL_CREDIT, NOW);

        assertThat(CsmEventDecision.decide(rejected, false, SETTLED, NOW)).isInstanceOf(Contradiction.class);
    }

    @Test
    void anOutcomeBeforeAnyAnswerIsAContradiction() {
        assertThat(CsmEventDecision.decide(IncomingPayments.received(), false, SETTLED, NOW))
                .isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(IncomingPayments.received(), false, REJECTED_AB05, NOW))
                .isInstanceOf(Contradiction.class);
    }

    @Test
    void aRejectionAfterTheAcceptanceRejectsWithTheReasonAndCancelsTheCredit() {
        var apply = (Apply) CsmEventDecision.decide(accepted(), false, REJECTED_AB05, NOW);

        assertThat(apply.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(apply.next().reasonCode()).isEqualTo(ReasonCode.AB05);
        assertThat(apply.next().pendingAction()).isEqualTo(PendingAction.CANCEL_CREDIT);
        assertThat(apply.next().finalisedAt()).isEqualTo(NOW);
        assertThat(apply.outcome()).isEqualTo(CsmOutcome.REJECTED);
        assertThat(apply.cause()).isEqualTo("CSM_REJECTED");
    }

    @Test
    void aRejectionOfASettledTransferIsAContradictionAndOfARejectedOneANoOp() {
        Payment confirming = ((Apply) CsmEventDecision.decide(accepted(), false, SETTLED, NOW)).next();

        assertThat(CsmEventDecision.decide(settled(), true, REJECTED_AB05, NOW)).isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(confirming, true, REJECTED_AB05, NOW)).isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(accepted().rejected(ReasonCode.AB05, PendingAction.CANCEL_CREDIT, NOW),
                false, REJECTED_AB05, NOW)).isInstanceOf(NoOp.class);
    }

    @Test
    void theConfirmedCreditSettlesThePayment() {
        UUID entry = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Payment confirming = ((Apply) CsmEventDecision.decide(accepted(), false, SETTLED, NOW)).next();

        Payment settled = confirming.settledFromCredit(entry, payee, NOW);

        assertThat(settled.state()).isEqualTo(PaymentState.SETTLED);
        assertThat(settled.pendingAction()).isNull();
        assertThat(settled.bookingEntryId()).isEqualTo(entry);
        assertThat(settled.creditorAccountId()).isEqualTo(payee);
        assertThat(settled.finalisedAt()).isEqualTo(NOW);
    }

    @Test
    void aCancelledCreditLeavesThePaymentAsItIsWithNothingToDo() {
        Payment confirming = ((Apply) CsmEventDecision.decide(accepted(), false, SETTLED, NOW)).next();

        Payment abandoned = confirming.confirmAbandoned();

        assertThat(abandoned.state()).isEqualTo(PaymentState.ACCEPTED);
        assertThat(abandoned.pendingAction()).isNull();
    }

    @Test
    void theAcceptingAnswerAfterTheSettlementOvertookItKeepsTheCreditsConfirmation() {
        Payment confirming = ((Apply) CsmEventDecision.decide(IncomingPayments.acceptRequested(), false, SETTLED,
                NOW)).next();

        var moved = (AnswerDecision.Moved) AnswerDecision.decide(confirming, AnswerDecision.Answer.ACCP, null, NOW);

        assertThat(moved.next().state()).isEqualTo(PaymentState.ACCEPTED);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.CONFIRM_CREDIT);
    }

    @Test
    void theAcceptingAnswerOfASettledTransferChangesNothing() {
        Payment settledFirst = ((Apply) CsmEventDecision.decide(IncomingPayments.acceptRequested(), false, SETTLED,
                NOW)).next().settledFromCredit(UUID.randomUUID(), UUID.randomUUID(), NOW);

        assertThat(AnswerDecision.decide(settledFirst, AnswerDecision.Answer.ACCP, null, NOW))
                .isInstanceOf(AnswerDecision.NoOp.class);
    }

    @Test
    void theAcceptingAnswerAfterTheCsmsRejectionOvertookItChangesNothing() {
        Payment rejectedFirst = ((Apply) CsmEventDecision.decide(IncomingPayments.acceptRequested(), false,
                REJECTED_AB05, NOW)).next();

        assertThat(AnswerDecision.decide(rejectedFirst, AnswerDecision.Answer.ACCP, null, NOW))
                .isInstanceOf(AnswerDecision.NoOp.class);
    }

    @Test
    void aRejectingAnswerAfterTheCsmSettledIsAContradiction() {
        Payment confirming = ((Apply) CsmEventDecision.decide(IncomingPayments.acceptRequested(), false, SETTLED,
                NOW)).next();

        assertThat(AnswerDecision.decide(confirming, AnswerDecision.Answer.RJCT, ReasonCode.AB05, NOW))
                .isInstanceOf(AnswerDecision.Contradiction.class);
    }

    @Test
    void aRepeatedAcceptingAnswerOfAnAcceptedTransferIsAContradiction() {
        assertThat(AnswerDecision.decide(accepted(), AnswerDecision.Answer.ACCP, null, NOW))
                .isInstanceOf(AnswerDecision.Contradiction.class);
    }

    private static Payment accepted() {
        return ((AnswerDecision.Moved) AnswerDecision.decide(IncomingPayments.acceptRequested(),
                AnswerDecision.Answer.ACCP, null, RECEIVED_AT.plusSeconds(1))).next();
    }

    private static Payment settled() {
        Payment confirming = ((Apply) CsmEventDecision.decide(accepted(), false, SETTLED, NOW)).next();
        return confirming.settledFromCredit(UUID.randomUUID(), UUID.randomUUID(), NOW);
    }
}
