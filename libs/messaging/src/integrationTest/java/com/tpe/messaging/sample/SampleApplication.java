package com.tpe.messaging.sample;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.messaging.consumer.ConsumedMessages;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A service that writes a business change and a message in one transaction, and consumes the message exactly once
 * (issue #14, the M0 exit criterion).
 */
@SpringBootApplication
public class SampleApplication {

    public static final String TOPIC = "sample.commands";

    /** Publishes through the outbox in the business transaction. */
    @Service
    public static class Accepting {

        private final OutboxWriter outbox;

        Accepting(OutboxWriter outbox) {
            this.outbox = outbox;
        }

        @Transactional
        public UUID accept(PaymentId payment) {
            return outbox.write(TOPIC, payment.value().toString(), message(payment));
        }

        /** Writes the message, then fails: the transaction rolls back. */
        @Transactional
        public void acceptThenFail(PaymentId payment) {
            outbox.write(TOPIC, payment.value().toString(), message(payment));
            throw new IllegalStateException("business rule failed after the outbox write");
        }

        public static AcceptCreditTransfer message(PaymentId payment) {
            return new AcceptCreditTransfer(payment, new TxId("PEER20261021000017"), new Bic("PEERDEFFXXX"),
                    Instant.parse("2026-10-21T09:16:01.125Z"));
        }
    }

    /** The business effect: one {@code sample_effects} row per message, deduplicated by the inbox. */
    @Service
    public static class Effects {

        private final InboxGuard inbox;
        private final JdbcTemplate jdbc;

        Effects(InboxGuard inbox, JdbcTemplate jdbc) {
            this.inbox = inbox;
            this.jdbc = jdbc;
        }

        @Transactional
        public void apply(ConsumerRecord<String, String> record) {
            AcceptCreditTransfer accept = ConsumedMessages.read(record, AcceptCreditTransfer.class);
            UUID eventId = ConsumedMessages.eventId(record);
            if (!inbox.firstTime(eventId.toString(), record.topic(), eventId)) {
                return;
            }
            jdbc.update("INSERT INTO sample_effects (event_id, payment_id) VALUES (?, ?)", eventId,
                    accept.paymentId().value());
        }

        public int count(PaymentId payment) {
            return jdbc.queryForObject("SELECT count(*) FROM sample_effects WHERE payment_id = ?", Integer.class,
                    payment.value());
        }
    }

    @Component
    static class Listener {

        private final Effects effects;

        Listener(Effects effects) {
            this.effects = effects;
        }

        @KafkaListener(topics = TOPIC)
        void onMessage(ConsumerRecord<String, String> record) {
            effects.apply(record);
        }
    }
}
