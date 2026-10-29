package com.tpe.observability.payment;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.Objects;

/**
 * What {@link PaymentTelemetry} puts on a span and into the MDC (specs/26-observability.md §5.3): typed identifiers
 * only, never a party, an IBAN, a name or free text (§4.4). The transaction identity {@code txId} +
 * {@code debtorAgentBic} is required (ADR-010); the rest is set when the caller knows it.
 *
 * @param acceptedAt start of the payment's budget, for {@code tpe.payment.elapsed_ms} (incoming: gateway receipt)
 */
public record PaymentTags(
        TxId txId, Bic debtorAgentBic, PaymentId paymentId, Uetr uetr, PaymentDirection direction, Boolean onUs,
        Instant acceptedAt) {

    public PaymentTags {
        Objects.requireNonNull(txId, "txId");
        Objects.requireNonNull(debtorAgentBic, "debtorAgentBic");
    }

    public static PaymentTags of(TxId txId, Bic debtorAgentBic) {
        return new PaymentTags(txId, debtorAgentBic, null, null, null, null, null);
    }

    public PaymentTags paymentId(PaymentId paymentId) {
        return new PaymentTags(txId, debtorAgentBic, paymentId, uetr, direction, onUs, acceptedAt);
    }

    public PaymentTags uetr(Uetr uetr) {
        return new PaymentTags(txId, debtorAgentBic, paymentId, uetr, direction, onUs, acceptedAt);
    }

    public PaymentTags direction(PaymentDirection direction) {
        return new PaymentTags(txId, debtorAgentBic, paymentId, uetr, direction, onUs, acceptedAt);
    }

    public PaymentTags onUs(boolean onUs) {
        return new PaymentTags(txId, debtorAgentBic, paymentId, uetr, direction, onUs, acceptedAt);
    }

    public PaymentTags acceptedAt(Instant acceptedAt) {
        return new PaymentTags(txId, debtorAgentBic, paymentId, uetr, direction, onUs, acceptedAt);
    }
}
