package com.tpe.csmgateway.api.commands;

import com.tpe.contracts.RequestPaymentStatus;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.csmgateway.application.investigation.PaymentStatusRequesting;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.messaging.consumer.ConsumedMessages;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The command listener (specs/15-csm-gateway.md §2, §4): {@code csm.outbound.commands}, group {@code csm-gateway}.
 * A command is read and validated ({@link ConsumedMessages#readOneOf}); an invalid one, or a command this gateway does
 * not handle yet (the verdicts, {@code RequestPaymentStatus} for an incoming transfer), goes to the DLT.
 */
@Component
class CsmCommandListener {

    private final CreditTransferSending sending;
    private final PaymentStatusRequesting statusRequesting;

    CsmCommandListener(CreditTransferSending sending, PaymentStatusRequesting statusRequesting) {
        this.sending = sending;
        this.statusRequesting = statusRequesting;
    }

    @KafkaListener(topics = CreditTransferSending.COMMANDS)
    void on(ConsumerRecord<String, String> record) {
        UUID eventId = ConsumedMessages.eventId(record);
        switch (ConsumedMessages.readOneOf(record, SendCreditTransfer.class, RequestPaymentStatus.class)) {
            case SendCreditTransfer command -> sending.send(eventId, command);
            case RequestPaymentStatus command -> statusRequesting.request(eventId, command);
            default -> throw new IllegalStateException("readOneOf returned another type");
        }
    }
}
