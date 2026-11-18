package com.tpe.csmsimulator.domain.scheme;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The scheme rules for a {@code pacs.008} the CSM delivers to our bank (specs/17-csm-simulator.md §3.2, incoming): the
 * CSM waits for our answer until {@code sentAt + answer-timeout}. An {@code ACCP} before then is settled and
 * confirmed; a {@code RJCT} before then is recorded; an answer at or after it, or a second answer, changes nothing.
 */
public final class IncomingScheme {

    /** What an answer does to a transaction still awaiting one. */
    public enum Effect {
        /** {@code ACCP} in time: settle and confirm. */
        SETTLE,
        /** {@code RJCT} in time: recorded, nothing else sent. */
        REJECT,
        /** Too late, or not an answer the CSM acts on: recorded only. */
        NONE
    }

    /** The {@code TxSts} codes of our answer (ISO 20022 ExternalPaymentTransactionStatus1Code). */
    private static final String ACCEPTED = "ACCP";
    private static final String REJECTED = "RJCT";

    private final Duration answerTimeout;

    public IncomingScheme(Duration answerTimeout) {
        this.answerTimeout = Objects.requireNonNull(answerTimeout, "answerTimeout");
    }

    /** When the CSM stops waiting for our answer to a {@code pacs.008} it sent at {@code sentAt}. */
    public Instant answerDeadline(Instant sentAt) {
        return sentAt.plus(answerTimeout);
    }

    /**
     * @param status our answer's {@code TxSts}
     * @param answeredAt when the CSM processes the answer
     */
    public Effect answer(Instant sentAt, String status, Instant answeredAt) {
        if (!answeredAt.isBefore(answerDeadline(sentAt))) {
            return Effect.NONE;
        }
        return switch (status) {
            case ACCEPTED -> Effect.SETTLE;
            case REJECTED -> Effect.REJECT;
            default -> Effect.NONE;
        };
    }
}
