package com.tpe.paymentapi.application.mapping;

import com.tpe.contracts.payment.PaymentState;

/**
 * The customer-facing status of an outgoing payment, ISO 20022 {@code ExternalPaymentTransactionStatus1Code}
 * (specs/10-payment-api.md §3.2, UC-07 3): {@code status} of the JSON responses and {@code TxSts} of the
 * {@code pain.002}.
 */
public enum CustomerStatus {
    /** Pending: not final yet. */
    PDNG,
    /** Accepted settlement completed. */
    ACSC,
    /** Rejected; the response carries the reason code. */
    RJCT;

    /** @throws IllegalArgumentException for a state only incoming payments have, which this API never shows */
    public static CustomerStatus of(PaymentState state) {
        return switch (state) {
            case RECEIVED, RISK_CHECKED, FUNDS_RESERVED, SENT_TO_CSM, INVESTIGATING -> PDNG;
            case SETTLED -> ACSC;
            case REJECTED -> RJCT;
            case ACCEPT_REQUESTED, ACCEPTED -> throw new IllegalArgumentException(
                    state + " is a state of incoming payments only");
        };
    }
}
