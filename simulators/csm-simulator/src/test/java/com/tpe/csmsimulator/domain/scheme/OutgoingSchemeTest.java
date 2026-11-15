package com.tpe.csmsimulator.domain.scheme;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.csmsimulator.domain.scenarios.Scenario.Accept;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Delay;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Reject;
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
                .isEqualTo(new Outcome(NOW.plusMillis(300), Status.SETTLED, true));
    }

    @Test
    void rejectAnswersWithTheReasonAtOnceAndDoesNotSettle() {
        assertThat(scheme.decide(ACCEPTED_AT, new Reject(ReasonCode.AC04), NOW))
                .isEqualTo(new Outcome(NOW, Status.rejected(ReasonCode.AC04), false));
    }

    @Test
    void timeoutRejectsWithAb05AtTheTimeOutDeadline() {
        assertThat(scheme.decide(ACCEPTED_AT, new Timeout(), NOW))
                .isEqualTo(new Outcome(DEADLINE, Status.rejected(ReasonCode.AB05), false));
    }

    @Test
    void delayAcceptsAfterItsOwnDelay() {
        assertThat(scheme.decide(ACCEPTED_AT, new Delay(Duration.ofSeconds(5)), NOW))
                .isEqualTo(new Outcome(NOW.plusSeconds(5), Status.SETTLED, true));
    }

    @Test
    void aBeneficiaryAnswerPastTheDeadlineIsATimeout() {
        assertThat(scheme.decide(ACCEPTED_AT, new Delay(Duration.ofSeconds(25)), NOW))
                .isEqualTo(new Outcome(DEADLINE, Status.rejected(ReasonCode.AB05), false));
    }

    @Test
    void aBeneficiaryAnswerExactlyAtTheDeadlineStillSettles() {
        Instant now = DEADLINE.minusSeconds(1);

        assertThat(scheme.decide(ACCEPTED_AT, new Delay(Duration.ofSeconds(1)), now))
                .isEqualTo(new Outcome(DEADLINE, Status.SETTLED, true));
    }

    @Test
    void aTransferArrivingPastTheDeadlineIsRejectedAb05AtOnceWhateverTheScenario() {
        Instant late = DEADLINE.plusMillis(1);

        assertThat(scheme.decide(ACCEPTED_AT, new Accept(), late))
                .isEqualTo(new Outcome(late, Status.rejected(ReasonCode.AB05), false));
        assertThat(scheme.decide(ACCEPTED_AT, new Reject(ReasonCode.AC04), late))
                .isEqualTo(new Outcome(late, Status.rejected(ReasonCode.AB05), false));
    }

    @Test
    void aTransferArrivingExactlyAtTheDeadlineIsNotLate() {
        assertThat(scheme.decide(ACCEPTED_AT, new Reject(ReasonCode.AC04), DEADLINE))
                .isEqualTo(new Outcome(DEADLINE, Status.rejected(ReasonCode.AC04), false));
    }
}
