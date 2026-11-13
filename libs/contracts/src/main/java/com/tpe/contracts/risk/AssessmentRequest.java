package com.tpe.contracts.risk;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;

/**
 * {@code POST /v1/risk/assessments} (specs/14-risk-service.md §3.1): a fraud decision on one payment, in one direction.
 *
 * @param assessmentId {@code <paymentId>:<direction>}; the same id answers the stored decision
 * @param amount written as {@code amountMinor} and {@code currency}
 * @param vopResult required for {@code OUTGOING}, absent for {@code INCOMING}
 */
public record AssessmentRequest(
        String assessmentId,
        PaymentId paymentId,
        Direction direction,
        TxId txId,
        @JsonUnwrapped Money amount,
        Party debtor,
        Party creditor,
        Bic debtorAgentBic,
        Bic creditorAgentBic,
        RemittanceInformation remittanceInformation,
        VopResult vopResult) {

    /** The {@code assessmentId} of a payment in a direction. */
    public static String assessmentId(PaymentId paymentId, Direction direction) {
        return paymentId.value() + ":" + direction.name();
    }

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "AssessmentRequest[assessmentId=" + assessmentId + ", direction=" + direction + ", amount=" + amount
                + "]";
    }
}
