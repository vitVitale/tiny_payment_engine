package com.tpe.paymentorchestrator.domain.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The time limits of an outgoing payment, as offsets from its acceptance (specs/11-payment-orchestrator.md §3.1, §7):
 * {@code send_by} for the transfer to leave, the scheme deadline after it, and how long before {@code send_by} a
 * failing hold is given up. They must keep {@code holdGiveUpMargin < sendByOffset < deadlineOffset}, or a payment
 * could be sent after its deadline or never get a hold attempt.
 */
public record OutgoingTimings(Duration sendByOffset, Duration deadlineOffset, Duration holdGiveUpMargin) {

    public OutgoingTimings {
        Objects.requireNonNull(sendByOffset, "sendByOffset");
        Objects.requireNonNull(deadlineOffset, "deadlineOffset");
        Objects.requireNonNull(holdGiveUpMargin, "holdGiveUpMargin");
        if (holdGiveUpMargin.isNegative() || holdGiveUpMargin.compareTo(sendByOffset) >= 0
                || sendByOffset.compareTo(deadlineOffset) >= 0) {
            throw new IllegalArgumentException("outgoing timings need 0 <= hold-give-up-margin (" + holdGiveUpMargin
                    + ") < send-by-offset (" + sendByOffset + ") < deadline-offset (" + deadlineOffset + ")");
        }
    }

    public Instant sendBy(Instant acceptedAt) {
        return acceptedAt.plus(sendByOffset);
    }

    public Instant deadline(Instant acceptedAt) {
        return acceptedAt.plus(deadlineOffset);
    }
}
