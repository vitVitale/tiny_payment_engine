package com.tpe.paymentorchestrator.application.payments;

import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.contracts.PaymentRejected;
import com.tpe.contracts.PaymentSettled;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import java.util.UUID;
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
        String key = payment.paymentId().value().toString();
        Direction direction = Direction.valueOf(payment.direction().name());
        Party debtor = party(payment.debtor(), payment.debtorAccountId());
        Party creditor = party(payment.creditor(), payment.creditorAccountId());
        switch (payment.state()) {
            case SETTLED -> outbox.write(TOPIC, key, new PaymentSettled(payment.paymentId(), direction,
                    payment.onUs(), payment.txId(), payment.debtor().agentBic(), payment.uetr(),
                    payment.endToEndId(), payment.amount(), debtor, creditor, payment.remittance(),
                    payment.acceptedAt(), payment.finalisedAt(), payment.bookingEntryId()));
            case REJECTED -> outbox.write(TOPIC, key, new PaymentRejected(payment.paymentId(), direction,
                    payment.onUs(), payment.txId(), payment.debtor().agentBic(), payment.uetr(),
                    payment.endToEndId(), payment.amount(), debtor, creditor, payment.remittance(),
                    payment.acceptedAt(), payment.finalisedAt(), payment.reasonCode()));
            default -> throw new IllegalArgumentException("no final event for " + payment.state());
        }
    }

    private static Party party(PaymentParty party, UUID accountId) {
        return new Party(party.name(), party.iban(), accountId);
    }
}
