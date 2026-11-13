package com.tpe.paymentorchestrator.application.payments;

import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.contracts.PaymentRejected;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import org.springframework.stereotype.Component;

/**
 * {@code PaymentSettled} / {@code PaymentRejected} on {@code payments.events}, key {@code paymentId}
 * (specs/02-messaging-contracts.md §6.2), written to the outbox in the transaction that finalises the payment
 * (specs/11-payment-orchestrator.md §6.2): exactly one per payment.
 */
@Component
public class FinalEvents {

    static final String TOPIC = "payments.events";

    private final OutboxWriter outbox;

    FinalEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Must run in the transaction that stores the final state. */
    public void emit(Payment payment) {
        if (payment.state() != PaymentState.REJECTED) {
            throw new IllegalArgumentException("no final event for " + payment.state() + " yet");
        }
        outbox.write(TOPIC, payment.paymentId().value().toString(), new PaymentRejected(payment.paymentId(),
                Direction.valueOf(payment.direction().name()), payment.onUs(), payment.txId(),
                payment.debtor().agentBic(), payment.uetr(), payment.endToEndId(), payment.amount(),
                party(payment.debtor()), party(payment.creditor()), payment.remittance(), payment.acceptedAt(),
                payment.finalisedAt(), payment.reasonCode()));
    }

    private static Party party(PaymentParty party) {
        return new Party(party.name(), party.iban(), null);
    }
}
