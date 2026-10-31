package com.tpe.messaging.sample;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.messaging.consumer.ConsumedMessages;
import com.tpe.messaging.consumer.DltHook;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.messaging.worker.PollingWorker;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * A service that writes a business change and a message in one transaction, and consumes the message exactly once
 * (issue #14, the M0 exit criterion). Its listener on {@link #EVENTS} shows the error handling (issue #15), its
 * {@code sample_timers} worker the {@link PollingWorker} (issue #17).
 */
@SpringBootApplication
public class SampleApplication {

    /** Fires due {@code sample_timers}. */
    @Bean
    PollingWorker<Long> sampleTimers(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            MeterRegistry registry) {
        return new PollingWorker<>(new PollingWorker.Settings("sample-timers", 50, Duration.ofSeconds(1), 1),
                jdbc, transactions, """
                        SELECT id FROM sample_timers WHERE status = 'SCHEDULED' AND due_at <= ?
                        ORDER BY due_at LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, row) -> rs.getLong("id"),
                id -> jdbc.update("UPDATE sample_timers SET status = 'FIRED' WHERE id = ?", id),
                "SELECT min(due_at) FROM sample_timers WHERE status = 'SCHEDULED' AND due_at <= ?",
                Clock.systemUTC(), registry);
    }

    public static final String TOPIC = "sample.commands";
    /** Accepts only {@code AcceptCreditTransfer}; its effects go to {@code sample_ordered_effects}. */
    public static final String EVENTS = "sample.events";

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

    /** The {@link #EVENTS} handler: one {@code sample_ordered_effects} row per message, counting every attempt. */
    @Service
    public static class OrderedEffects {

        private final InboxGuard inbox;
        private final JdbcTemplate jdbc;
        private final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();

        OrderedEffects(InboxGuard inbox, JdbcTemplate jdbc) {
            this.inbox = inbox;
            this.jdbc = jdbc;
        }

        @Transactional
        public void apply(ConsumerRecord<String, String> record) {
            UUID eventId = ConsumedMessages.eventId(record);
            attempts.computeIfAbsent(eventId, id -> new AtomicInteger()).incrementAndGet();
            var accept = (AcceptCreditTransfer) ConsumedMessages.readOneOf(record, AcceptCreditTransfer.class);
            if (!inbox.firstTime(eventId.toString(), record.topic(), eventId)) {
                return;
            }
            jdbc.update("INSERT INTO sample_ordered_effects (message_key, event_id, payment_id) VALUES (?, ?, ?)",
                    record.key(), eventId, accept.paymentId().value());
        }

        /** How often the listener was called with the record of {@code eventId}. */
        public int attempts(UUID eventId) {
            AtomicInteger count = attempts.get(eventId);
            return count == null ? 0 : count.get();
        }

        /** The event ids of {@code key}'s effects, in the order they were applied. */
        public List<UUID> applied(String key) {
            return jdbc.queryForList("SELECT event_id FROM sample_ordered_effects WHERE message_key = ? ORDER BY id",
                    UUID.class, key);
        }
    }

    /** Records every DLT publish it is told about. */
    @Component
    public static class RecordingDltHook implements DltHook {

        public record Call(String topic, String key, Exception cause) {}

        private final List<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public void onDeadLetter(ConsumerRecord<?, ?> record, Exception cause) {
            calls.add(new Call(record.topic(), String.valueOf(record.key()), cause));
        }

        public List<Call> calls() {
            return List.copyOf(calls);
        }
    }

    @Component
    static class EventsListener {

        private final OrderedEffects effects;

        EventsListener(OrderedEffects effects) {
            this.effects = effects;
        }

        @KafkaListener(topics = EVENTS)
        void onMessage(ConsumerRecord<String, String> record) {
            effects.apply(record);
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
