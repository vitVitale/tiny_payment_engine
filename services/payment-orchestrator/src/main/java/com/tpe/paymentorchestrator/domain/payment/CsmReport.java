package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.StatusReason;
import java.util.Objects;

/**
 * What csm-gateway reports about a payment's outcome at the CSM on {@code csm.inbound.events}
 * (specs/02-messaging-contracts.md §4): settled or rejected, either direction; never sent, outgoing only.
 */
public sealed interface CsmReport {

    /** {@code CreditTransferSettled}. */
    record Settled() implements CsmReport {}

    /** {@code CreditTransferRejected} with the CSM's reason. */
    record Rejected(StatusReason reason) implements CsmReport {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** {@code CreditTransferNotSent}: {@code AB03}, or {@code FF01} for an invalid {@code pacs.008}. */
    record NotSent(StatusReason reason) implements CsmReport {

        public NotSent {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
