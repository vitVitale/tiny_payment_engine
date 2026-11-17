package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.StatusReason;
import java.util.Objects;

/**
 * What csm-gateway reports about an outgoing payment on {@code csm.inbound.events}
 * (specs/02-messaging-contracts.md §4).
 */
public sealed interface OutgoingCsmEvent {

    /** {@code CreditTransferSettled}. */
    record Settled() implements OutgoingCsmEvent {}

    /** {@code CreditTransferRejected} with the CSM's reason. */
    record Rejected(StatusReason reason) implements OutgoingCsmEvent {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** {@code CreditTransferNotSent}: {@code AB03}, or {@code FF01} for an invalid {@code pacs.008}. */
    record NotSent(StatusReason reason) implements OutgoingCsmEvent {

        public NotSent {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
