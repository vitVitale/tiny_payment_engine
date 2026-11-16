package com.tpe.csmgateway.api.commands;

import com.tpe.contracts.SendCreditTransfer;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.messaging.consumer.ConsumedMessages;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The command listener (specs/15-csm-gateway.md §2, §4): {@code csm.outbound.commands}, group {@code csm-gateway}.
 * A command is read and validated ({@link ConsumedMessages#readOneOf}); an invalid one, or a command type this
 * gateway does not handle yet ({@code RequestPaymentStatus}, the verdicts), goes to the DLT.
 */
@Component
class CsmCommandListener {

    private final CreditTransferSending sending;

    CsmCommandListener(CreditTransferSending sending) {
        this.sending = sending;
    }

    @KafkaListener(topics = CreditTransferSending.COMMANDS)
    void on(ConsumerRecord<String, String> record) {
        UUID eventId = ConsumedMessages.eventId(record);
        var command = (SendCreditTransfer) ConsumedMessages.readOneOf(record, SendCreditTransfer.class);
        sending.send(eventId, command);
    }
}
