package com.tpe.csmsimulator.domain.scheme;

import com.tpe.csmsimulator.domain.scenarios.IncomingScenario;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The scheme rules for a {@code pacs.008} the CSM delivers to our bank (specs/17-csm-simulator.md §3.2, incoming): the
 * CSM waits for our answer until {@code sentAt + answer-timeout}. An {@code ACCP} before then is settled and
 * confirmed, unless the scenario says otherwise; a {@code RJCT} before then is recorded; an answer at or after it, or
 * a second answer, changes nothing. No answer in time is the CSM's rejection {@code AB05}.
 */
public final class IncomingScheme {

    /**
     * What our answer makes of the transaction.
     *
     * @param finalStatus the status the CSM answers investigations with from now on
     * @param settles whether the CSM settles the transaction now
     * @param reported whether the CSM sends {@code finalStatus} to us in a {@code pacs.002} now
     */
    public record Outcome(Status finalStatus, boolean settles, boolean reported) {

        public Outcome {
            Objects.requireNonNull(finalStatus, "finalStatus");
        }

        static Outcome settledAndConfirmed() {
            return new Outcome(Status.SETTLED, true, true);
        }

        static Outcome settledUnconfirmed() {
            return new Outcome(Status.SETTLED, true, false);
        }

        static Outcome rejectedByTheCsm() {
            return new Outcome(CSM_REJECTION, false, true);
        }

        static Outcome rejectedByUs(StatusReason reason) {
            return new Outcome(Status.rejected(Objects.requireNonNull(reason, "reason")), false, false);
        }
    }

    /** The {@code TxSts} codes of our answer (ISO 20022 ExternalPaymentTransactionStatus1Code). */
    public static final String ACCEPTED = "ACCP";
    public static final String REJECTED = "RJCT";

    /** The CSM's own rejection: AB05, as for a time-out (UC-06 8a). */
    private static final Status CSM_REJECTION = Status.rejected(ReasonCode.AB05);

    private final Duration answerTimeout;

    public IncomingScheme(Duration answerTimeout) {
        this.answerTimeout = Objects.requireNonNull(answerTimeout, "answerTimeout");
    }

    /** When the CSM stops waiting for our answer to a {@code pacs.008} it sent at {@code sentAt}. */
    public Instant answerDeadline(Instant sentAt) {
        return sentAt.plus(answerTimeout);
    }

    /** The final status of a transaction we did not answer in time: the CSM's rejection, never reported to us. */
    public Status timedOut() {
        return CSM_REJECTION;
    }

    /**
     * Our answer to a transaction still awaiting one.
     *
     * @param status our answer's {@code TxSts}
     * @param reason our rejection's reason; required for a {@code RJCT}
     * @param answeredAt when the CSM processes the answer
     * @return empty if the answer changes nothing: too late, or neither {@code ACCP} nor {@code RJCT}
     */
    public Optional<Outcome> answer(Instant sentAt, IncomingScenario scenario, String status, StatusReason reason,
            Instant answeredAt) {
        if (!answeredAt.isBefore(answerDeadline(sentAt))) {
            return Optional.empty();
        }
        return switch (status) {
            case ACCEPTED -> Optional.of(switch (scenario) {
                case ACCEPT -> Outcome.settledAndConfirmed();
                case REJECT_AFTER_ACCP -> Outcome.rejectedByTheCsm();
                case NO_CONFIRMATION -> Outcome.settledUnconfirmed();
            });
            case REJECTED -> Optional.of(Outcome.rejectedByUs(reason));
            default -> Optional.empty();
        };
    }
}
