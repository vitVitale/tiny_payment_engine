package com.tpe.csmsimulator.domain.scheme;

import com.tpe.csmsimulator.domain.scenarios.Scenario;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Accept;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Delay;
import com.tpe.csmsimulator.domain.scenarios.Scenario.InvestigationSilent;
import com.tpe.csmsimulator.domain.scenarios.Scenario.LateStatus;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Lost;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Reject;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Silent;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Timeout;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The scheme rules for a {@code pacs.008} from our bank (specs/17-csm-simulator.md §3.2, outgoing): the time-out
 * deadline is {@code AccptncDtTm + timeout-deadline}. A transfer arriving after it is rejected {@code AB05} at once and
 * never settled; otherwise the creditor's scenario decides. A beneficiary answer that would come after the deadline is
 * a time-out: {@code AB05} at the deadline, no settlement. A {@code LOST} transfer has no outcome: the CSM dropped it.
 */
public final class OutgoingScheme {

    /**
     * The final status and when the CSM reaches it, and when it reports it in a {@code pacs.002}.
     *
     * @param at when the status is final; a settled transfer is settled then
     * @param settles whether the CSM settles the transfer, at {@code at}
     * @param reportAt when the {@code pacs.002} goes, not before {@code at}; {@code null} if it never does
     */
    public record Outcome(Instant at, Status status, boolean settles, Instant reportAt) {

        public Outcome {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(status, "status");
            if (reportAt != null && reportAt.isBefore(at)) {
                throw new IllegalArgumentException("reported before final: " + reportAt + " < " + at);
            }
        }

        /** Reported as soon as it is final. */
        public Outcome(Instant at, Status status, boolean settles) {
            this(at, status, settles, at);
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
    public Optional<Outcome> decide(Instant acceptedAt, Scenario scenario, Instant now) {
        Instant deadline = acceptedAt.plus(timeoutDeadline);
        if (now.isAfter(deadline)) {
            return Optional.of(timedOut(now));
        }
        Outcome accepted = beneficiaryAccepts(now.plus(beneficiaryDelay), deadline);
        return switch (scenario.payment()) {
            case Accept accept -> Optional.of(accepted);
            case Delay delay -> Optional.of(beneficiaryAccepts(now.plus(delay.delay()), deadline));
            case Reject reject -> Optional.of(new Outcome(now, Status.rejected(reject.reason()), false));
            case Timeout timeout -> Optional.of(timedOut(deadline));
            case Silent silent -> Optional.of(unreported(accepted));
            case Lost lost -> Optional.empty();
            case LateStatus late -> Optional.of(reportedNotBefore(accepted, acceptedAt.plus(late.delay())));
            case InvestigationSilent nested -> throw new IllegalStateException("payment() is never " + nested);
        };
    }

    private static Outcome beneficiaryAccepts(Instant answeredAt, Instant deadline) {
        return answeredAt.isAfter(deadline) ? timedOut(deadline) : new Outcome(answeredAt, Status.SETTLED, true);
    }

    /** A settlement goes unreported; a time-out is still reported, as the CSM's own rejection. */
    private static Outcome unreported(Outcome outcome) {
        return outcome.settles() ? new Outcome(outcome.at(), outcome.status(), true, null) : outcome;
    }

    private static Outcome reportedNotBefore(Outcome outcome, Instant reportAt) {
        if (!outcome.settles() || !reportAt.isAfter(outcome.at())) {
            return outcome;
        }
        return new Outcome(outcome.at(), outcome.status(), true, reportAt);
    }

    private static Outcome timedOut(Instant at) {
        return new Outcome(at, Status.rejected(ReasonCode.AB05), false);
    }
}
