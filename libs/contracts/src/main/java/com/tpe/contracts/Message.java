package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;

/**
 * A Kafka message (specs/02-messaging-contracts.md). The JSON body is the record; {@link #type()} and
 * {@link #schemaVersion()}, declared by {@link Contract}, travel in headers, not in the body (02 §1). Unknown fields
 * are ignored on read, so a consumer reads samples of newer versions too (01 §4).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface Message
        permits SendCreditTransfer, RequestPaymentStatus, AcceptCreditTransfer, RejectCreditTransfer,
                CreditTransferReceived, AnswerSent, CreditTransferSettled, CreditTransferRejected,
                CreditTransferNotSent, AccountEvent, PaymentSettled, PaymentRejected {

    /** The message type, e.g. {@code SendCreditTransfer}. */
    @JsonIgnore
    default String type() {
        return contractOf(getClass()).type();
    }

    /** The schema version of this record, starting at 1. */
    @JsonIgnore
    default int schemaVersion() {
        return contractOf(getClass()).version();
    }

    /** The {@link Contract} of a message record. */
    static Contract contractOf(Class<? extends Message> type) {
        return Objects.requireNonNull(type.getAnnotation(Contract.class), () -> type.getName() + " has no @Contract");
    }
}
