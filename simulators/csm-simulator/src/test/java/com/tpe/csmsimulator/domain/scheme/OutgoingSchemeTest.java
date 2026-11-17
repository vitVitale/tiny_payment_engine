package com.tpe.csmsimulator.domain.scheme;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.csmsimulator.domain.scenarios.Scenario.Accept;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Delay;
import com.tpe.csmsimulator.domain.scenarios.Scenario.InvestigationSilent;
import com.tpe.csmsimulator.domain.scenarios.Scenario.LateStatus;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Lost;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Reject;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Silent;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Timeout;
import com.tpe.csmsimulator.domain.scheme.OutgoingScheme.Outcome;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutgoingSchemeTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-15T10:00:00.000Z");
    private static final Instant DEADLINE = ACCEPTED_AT.plusSeconds(20);
    private static final Instant NOW = ACCEPTED_AT.plusMillis(150);

    private final OutgoingScheme scheme = new OutgoingScheme(Duration.ofMillis(300), Duration.ofSeconds(20));

    @Test
    void acceptSettlesAndAnswersSettledAfterTheBeneficiaryDelay() {
        assertThat(scheme.decide(ACCEPTED_AT, new Accept(), NOW))
                .contains(new Outcome(NOW.plusMillis(300), Status.SETTLED, true));
    }

    @Test
    void rejectAnswersWithTheReasonAtOnceAndDoesNotSettle() {
        assertThat(scheme.decide(ACCEPTED_AT, new Reject(ReasonCode.AC04), NOW))
                .contains(new Outcome(NOW, Status.rejected(ReasonCode.AC04), false));
    }

    @Test
    void timeoutRejectsWithAb05AtTheTimeOutDeadline() {
        assertThat(scheme.decide(ACCEPTED_AT, new Timeout(), NOW))
                .contains(new Outcome(DEADLINE, Status.rejected(ReasonCode.AB05), false));
    }

    @Test
    void delayAcceptsAfterItsOwnDelay() {
        assertThat(scheme.decide(ACCEPTED_AT, new Delay(Duration.ofSeconds(5)), NOW))
                .contains(new Outcome(NOW.plusSeconds(5), Status.SETTLED, true));
    }

    @Test
    void aBeneficiaryAnswerPastTheDeadlineIsATimeout() {
        assertThat(scheme.decide(ACCEPTED_AT, new Delay(Duration.ofSeconds(25)), NOW))
                .contains(new Outcome(DEADLINE, Status.rejected(ReasonCode.AB05), false));
    }

    @Test
    void aBeneficiaryAnswerExactlyAtTheDeadlineStillSettles() {
        Instant now = DEADLINE.minusSeconds(1);

        assertThat(scheme.decide(ACCEPTED_AT, new Delay(Duration.ofSeconds(1)), now))
                .contains(new Outcome(DEADLINE, Status.SETTLED, true));
    }

    @Test
    void aTransferArrivingPastTheDeadlineIsRejectedAb05AtOnceWhateverTheScenario() {
        Instant late = DEADLINE.plusMillis(1);

        assertThat(scheme.decide(ACCEPTED_AT, new Accept(), late))
                .contains(new Outcome(late, Status.rejected(ReasonCode.AB05), false));
        assertThat(scheme.decide(ACCEPTED_AT, new Reject(ReasonCode.AC04), late))
                .contains(new Outcome(late, Status.rejected(ReasonCode.AB05), false));
    }

    @Test
    void aTransferArrivingExactlyAtTheDeadlineIsNotLate() {
        assertThat(scheme.decide(ACCEPTED_AT, new Reject(ReasonCode.AC04), DEADLINE))
                .contains(new Outcome(DEADLINE, Status.rejected(ReasonCode.AC04), false));
    }

    @Test
    void silentSettlesLikeAcceptButReportsNothing() {
        assertThat(scheme.decide(ACCEPTED_AT, new Silent(), NOW))
                .contains(new Outcome(NOW.plusMillis(300), Status.SETTLED, true, null));
    }

    @Test
    void lostDropsTheTransfer() {
        assertThat(scheme.decide(ACCEPTED_AT, new Lost(), NOW)).isEmpty();
    }

    @Test
    void aLostTransferArrivingPastTheDeadlineIsStillRejectedAb05() {
        Instant late = DEADLINE.plusMillis(1);

        assertThat(scheme.decide(ACCEPTED_AT, new Lost(), late))
                .contains(new Outcome(late, Status.rejected(ReasonCode.AB05), false));
    }

    @Test
    void lateStatusSettlesLikeAcceptAndReportsAtAcceptanceTimePlusItsDelay() {
        assertThat(scheme.decide(ACCEPTED_AT, new LateStatus(Duration.ofSeconds(21)), NOW)).contains(
                new Outcome(NOW.plusMillis(300), Status.SETTLED, true, ACCEPTED_AT.plusSeconds(21)));
    }

    @Test
    void aLateStatusDueBeforeTheSettlementIsReportedWhenSettled() {
        assertThat(scheme.decide(ACCEPTED_AT, new LateStatus(Duration.ofMillis(100)), NOW))
                .contains(new Outcome(NOW.plusMillis(300), Status.SETTLED, true));
    }

    @Test
    void investigationSilentPlaysItsPaymentScenario() {
        assertThat(scheme.decide(ACCEPTED_AT, new InvestigationSilent(new Silent()), NOW))
                .contains(new Outcome(NOW.plusMillis(300), Status.SETTLED, true, null));
        assertThat(scheme.decide(ACCEPTED_AT, new InvestigationSilent(new Accept()), NOW))
                .contains(new Outcome(NOW.plusMillis(300), Status.SETTLED, true));
    }
}
