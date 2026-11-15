package com.tpe.csmsimulator.domain.scheme;

import com.tpe.csmsimulator.domain.scenarios.Scenario;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Accept;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Delay;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Reject;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Timeout;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The scheme rules for a {@code pacs.008} from our bank (specs/17-csm-simulator.md §3.2, outgoing): the time-out
 * deadline is {@code AccptncDtTm + timeout-deadline}. A transfer arriving after it is rejected {@code AB05} at once and
 * never settled; otherwise the creditor's scenario decides. A beneficiary answer that would come after the deadline is
 * a time-out: {@code AB05} at the deadline, no settlement.
 */
public final class OutgoingScheme {

    /**
     * The {@code pacs.002} the CSM sends and when; a settled transfer is settled at that same instant.
     *
     * @param settles whether the CSM settles the transfer, at {@code at}
     */
    public record Outcome(Instant at, Status status, boolean settles) {

        public Outcome {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(status, "status");
        }
    }

    private final Duration beneficiaryDelay;
    private final Duration timeoutDeadline;

    public OutgoingScheme(Duration beneficiaryDelay, Duration timeoutDeadline) {
        this.beneficiaryDelay = Objects.requireNonNull(beneficiaryDelay, "beneficiaryDelay");
        this.timeoutDeadline = Objects.requireNonNull(timeoutDeadline, "timeoutDeadline");
    }

    /**
     * @param acceptedAt the transfer's {@code AccptncDtTm}
     * @param now when the CSM processes it
     */
    public Outcome decide(Instant acceptedAt, Scenario scenario, Instant now) {
        Instant deadline = acceptedAt.plus(timeoutDeadline);
        if (now.isAfter(deadline)) {
            return timedOut(now);
        }
        return switch (scenario) {
            case Accept accept -> beneficiaryAccepts(now.plus(beneficiaryDelay), deadline);
            case Delay delay -> beneficiaryAccepts(now.plus(delay.delay()), deadline);
            case Reject reject -> new Outcome(now, Status.rejected(reject.reason()), false);
            case Timeout timeout -> timedOut(deadline);
        };
    }

    private static Outcome beneficiaryAccepts(Instant answeredAt, Instant deadline) {
        return answeredAt.isAfter(deadline) ? timedOut(deadline) : new Outcome(answeredAt, Status.SETTLED, true);
    }

    private static Outcome timedOut(Instant at) {
        return new Outcome(at, Status.rejected(ReasonCode.AB05), false);
    }
}
