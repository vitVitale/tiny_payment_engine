package com.tpe.paymentorchestrator.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.Apply;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.Contradiction;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.NoOp;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent.NotSent;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent.Rejected;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent.Settled;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The CSM events of an outgoing payment (specs/11-payment-orchestrator.md §6.5). */
class CsmEventDecisionTest {

    private static final Instant ACCEPTED = Instant.parse("2026-11-13T10:00:00Z");
    private static final Instant NOW = ACCEPTED.plusSeconds(3);

    @Test
    void settledRecordsTheOutcomeAndSettlesTheHoldNext() {
        assertThat(CsmEventDecision.decide(sent(), false, new Settled(), NOW)).isInstanceOfSatisfying(Apply.class, apply -> {
            assertThat(apply.outcome()).isEqualTo(CsmOutcome.SETTLED);
            assertThat(apply.next().state()).as("SETTLED once the hold is").isEqualTo(PaymentState.SENT_TO_CSM);
            assertThat(apply.next().pendingAction()).isEqualTo(PendingAction.SETTLE_HOLD);
            assertThat(apply.cause()).isEqualTo("CSM_SETTLED");
        });
    }

    @Test
    void rejectedRejectsWithTheReasonAndReleasesTheHold() {
        assertThat(CsmEventDecision.decide(sent(), false, new Rejected(ReasonCode.AC04), NOW))
                .isInstanceOfSatisfying(Apply.class, apply -> {
                    assertThat(apply.outcome()).isEqualTo(CsmOutcome.REJECTED);
                    assertThat(apply.next().state()).isEqualTo(PaymentState.REJECTED);
                    assertThat(apply.next().reasonCode()).isEqualTo(ReasonCode.AC04);
                    assertThat(apply.next().pendingAction()).isEqualTo(PendingAction.RELEASE_HOLD);
                    assertThat(apply.next().finalisedAt()).isEqualTo(NOW);
                    assertThat(apply.cause()).isEqualTo("CSM_REJECTED");
                });
    }

    @Test
    void notSentRejectsWithItsReasonAndReleasesTheHold() {
        assertThat(CsmEventDecision.decide(sent(), false, new NotSent(ReasonCode.AB03), NOW))
                .isInstanceOfSatisfying(Apply.class, apply -> {
                    assertThat(apply.outcome()).isEqualTo(CsmOutcome.NOT_SENT);
                    assertThat(apply.next().reasonCode()).isEqualTo(ReasonCode.AB03);
                    assertThat(apply.next().pendingAction()).isEqualTo(PendingAction.RELEASE_HOLD);
                    assertThat(apply.cause()).isEqualTo("CSM_NOT_SENT");
                });
    }

    @Test
    void theSameFinalOutcomeAgainIsANoOp() {
        Payment settled = sent().csmSettled().settledFromHold(UUID.randomUUID(), NOW);
        Payment rejected = sent().rejected(ReasonCode.AC04, null, NOW);

        assertThat(CsmEventDecision.decide(settled, false, new Settled(), NOW)).isInstanceOf(NoOp.class);
        assertThat(CsmEventDecision.decide(rejected, false, new Rejected(ReasonCode.AB05), NOW)).isInstanceOf(NoOp.class);
        assertThat(CsmEventDecision.decide(rejected, false, new NotSent(ReasonCode.AB03), NOW)).isInstanceOf(NoOp.class);
    }

    @Test
    void aSettlementWhoseHoldIsBeingSettledIsANoOp() {
        assertThat(CsmEventDecision.decide(sent().csmSettled(), false, new Settled(), NOW)).isInstanceOf(NoOp.class);
    }

    @Test
    void ORC_AC_10_aContradictingFinalOutcomeIsAContradiction() {
        Payment rejected = sent().rejected(ReasonCode.AC04, PendingAction.RELEASE_HOLD, NOW);
        Payment settled = sent().csmSettled().settledFromHold(UUID.randomUUID(), NOW);

        assertThat(CsmEventDecision.decide(rejected, false, new Settled(), NOW)).isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(settled, false, new Rejected(ReasonCode.AB05), NOW))
                .isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(settled, false, new NotSent(ReasonCode.AB03), NOW))
                .isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(sent().csmSettled(), false, new Rejected(ReasonCode.AB05), NOW))
                .as("settled by the CSM, its hold not yet").isInstanceOf(Contradiction.class);
    }

    @Test
    void afterTheHoldWasReleasedUnderTheSettlementALateRejectionIsStillAContradiction() {
        Payment abandoned = sent().csmSettled().settleAbandoned();

        assertThat(CsmEventDecision.decide(abandoned, true, new Rejected(ReasonCode.AB05), NOW))
                .isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(abandoned, true, new NotSent(ReasonCode.AB03), NOW))
                .isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(abandoned, true, new Settled(), NOW)).isInstanceOf(NoOp.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"RECEIVED", "RISK_CHECKED", "FUNDS_RESERVED"})
    void anyEventBeforeThePaymentWasSentIsAContradiction(PaymentState state) {
        Payment payment = switch (state) {
            case RECEIVED -> submitted();
            case RISK_CHECKED -> submitted().riskChecked();
            default -> submitted().riskChecked().fundsReserved(UUID.randomUUID());
        };

        assertThat(CsmEventDecision.decide(payment, false, new Settled(), NOW)).isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(payment, false, new Rejected(ReasonCode.AC04), NOW))
                .isInstanceOf(Contradiction.class);
        assertThat(CsmEventDecision.decide(payment, false, new NotSent(ReasonCode.AB03), NOW))
                .isInstanceOf(Contradiction.class);
    }

    private static Payment sent() {
        return submitted().riskChecked().fundsReserved(UUID.randomUUID()).sent();
    }

    private static Payment submitted() {
        var command = new OutgoingCommand(new CommandId(UUID.randomUUID()), "mobile-app",
                new EndToEndId("INV-1"), new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"),
                new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null, Money.parse("123.45"),
                null, "VOP-1", PayeeVerification.MATCH);
        return Payment.submitted(new PaymentId(UUID.randomUUID()), command, "0".repeat(64),
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"),
                new PaymentParty(command.debtorName(), command.debtorIban(), new Bic("TPEBDEFFXXX")),
                new PaymentParty(command.creditorName(), command.creditorIban(), new Bic("PEERDEFFXXX")), false,
                ACCEPTED, new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2)),
                null);
    }
}
