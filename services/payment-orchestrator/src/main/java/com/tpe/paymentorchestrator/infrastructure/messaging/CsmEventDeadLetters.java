package com.tpe.paymentorchestrator.infrastructure.messaging;

import com.tpe.messaging.consumer.DltHook;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.application.csm.CsmEventHandling;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import java.time.Clock;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A CSM event that went to {@code csm.inbound.events.dlt} opens a {@code DLT_MESSAGE} case
 * (specs/11-payment-orchestrator.md §4, §5): linked to the payment if the record's key, its {@code txId}, names
 * exactly one, otherwise without a payment. The back office looks at it with the DLT tooling; the case names where
 * the record is and why it failed, never its payload.
 */
@Component
class CsmEventDeadLetters implements DltHook {

    private static final Logger LOG = LoggerFactory.getLogger(CsmEventDeadLetters.class);

    private final Payments payments;
    private final Clock clock;

    CsmEventDeadLetters(Payments payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Override
    public void onDeadLetter(ConsumerRecord<?, ?> record, Exception cause) {
        if (!CsmEventHandling.EVENTS.equals(record.topic())) {
            return;
        }
        PaymentId payment = paymentOf(record.key());
        payments.openCase(payment, ExceptionCaseType.DLT_MESSAGE, Map.of("topic", record.topic(),
                "partition", record.partition(), "offset", record.offset(),
                "key", String.valueOf(record.key()), "cause", cause.getClass().getSimpleName()), clock.instant());
        LOG.warn("{} record {}/{} dead-lettered: DLT_MESSAGE case opened{}", record.topic(), record.partition(),
                record.offset(), payment == null ? "" : " for payment " + payment.value());
    }

    private PaymentId paymentOf(Object key) {
        try {
            return key == null ? null : payments.findOnlyByTxId(new TxId(key.toString())).orElse(null);
        } catch (DomainValidationException e) {
            // a key that is no TxId names no payment
            return null;
        }
    }
}
