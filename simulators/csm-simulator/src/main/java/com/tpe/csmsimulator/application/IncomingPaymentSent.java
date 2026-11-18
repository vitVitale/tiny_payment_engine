package com.tpe.csmsimulator.application;

import com.tpe.paymentdomain.TxId;
import java.util.Objects;

/**
 * An incoming payment the CSM sent to the gateway.
 *
 * @param gatewayStatus the HTTP status csm-gateway answered; a payment it did not take ({@code non-2xx}) is forgotten
 */
public record IncomingPaymentSent(TxId txId, int gatewayStatus) {

    public IncomingPaymentSent {
        Objects.requireNonNull(txId, "txId");
    }
}
