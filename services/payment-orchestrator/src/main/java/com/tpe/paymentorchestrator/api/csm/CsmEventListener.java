package com.tpe.paymentorchestrator.api.csm;

import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.messaging.consumer.ConsumedMessages;
import com.tpe.paymentorchestrator.application.csm.CsmEventHandling;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The CSM event listener (specs/11-payment-orchestrator.md §4, §6.5): {@code csm.inbound.events}. An event is read
 * and validated ({@link ConsumedMessages#readOneOf}); an invalid one, or a type this service does not handle yet
 * ({@code CreditTransferReceived}, {@code AnswerSent}), goes to the DLT, where it opens a {@code DLT_MESSAGE} case.
 */
@Component
class CsmEventListener {

    private final CsmEventHandling handling;

    CsmEventListener(CsmEventHandling handling) {
        this.handling = handling;
    }

    @KafkaListener(topics = CsmEventHandling.EVENTS)
    void on(ConsumerRecord<String, String> record) {
        handling.handle(ConsumedMessages.eventId(record), ConsumedMessages.readOneOf(record,
                CreditTransferSettled.class, CreditTransferRejected.class, CreditTransferNotSent.class));
    }
}
