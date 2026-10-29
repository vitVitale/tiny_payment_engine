package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.messaging.sample.SampleApplication;
import com.tpe.messaging.sample.SampleApplication.Accepting;
import com.tpe.messaging.sample.SampleApplication.Effects;
import com.tpe.paymentdomain.PaymentId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The M0 tracer bullet: business change + message in one transaction → outbox → relay → Kafka → inbox. */
@SpringBootTest(classes = SampleApplication.class, properties = {
    "tpe.messaging.enabled=true",
    "spring.application.name=sample-service"
})
class OutboxToInboxIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        MessagingContainers.register(registry);
    }

    @Autowired
    Accepting accepting;

    @Autowired
    Effects effects;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void everyMessageHasExactlyOneBusinessEffect() {
        List<PaymentId> payments = List.of(payment(), payment(), payment());
        payments.forEach(accepting::accept);

        payments.forEach(payment -> Topics.await(Duration.ofSeconds(20), () -> effects.count(payment) == 1));
        pause(Duration.ofSeconds(1));
        assertThat(payments).allSatisfy(payment -> assertThat(effects.count(payment)).isEqualTo(1));
    }

    @Test
    void MSG_AC_01_rowOfARolledBackTransactionIsNeverPublished() {
        PaymentId rolledBack = payment();
        PaymentId committed = payment();

        assertThatThrownBy(() -> accepting.acceptThenFail(rolledBack)).isInstanceOf(IllegalStateException.class);
        accepting.accept(committed);

        Topics.await(Duration.ofSeconds(20), () -> effects.count(committed) == 1);
        assertThat(effects.count(rolledBack)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ?", Integer.class,
                rolledBack.value().toString())).isZero();
    }

    @Test
    void writeOutsideATransactionThrows() {
        PaymentId payment = payment();

        assertThatThrownBy(() -> outbox.write(SampleApplication.TOPIC, payment.value().toString(),
                Accepting.message(payment)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishedRecordsCarryTheHeaders() {
        PaymentId payment = payment();
        UUID eventId = accepting.accept(payment);

        ConsumerRecord<String, String> record = recordOf(eventId);

        assertThat(record.key()).isEqualTo(payment.value().toString());
        assertThat(Topics.header(record, "tpe-message-type")).isEqualTo("AcceptCreditTransfer");
        assertThat(Topics.header(record, "tpe-schema-version")).isEqualTo("1");
        assertThat(Topics.header(record, "tpe-producer")).isEqualTo("sample-service");
        assertThat(Topics.header(record, "tpe-occurred-at")).matches("\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z");
        assertThat(UUID.fromString(Topics.header(record, "tpe-event-id")).version()).isEqualTo(7);
    }

    @Test
    void MSG_AC_06_theSameMessageConsumedTwiceHasOneBusinessEffect() {
        PaymentId payment = payment();
        UUID eventId = accepting.accept(payment);
        Topics.await(Duration.ofSeconds(20), () -> effects.count(payment) == 1);
        double duplicatesBefore = duplicates();

        republish(recordOf(eventId));

        Topics.await(Duration.ofSeconds(20), () -> duplicates() > duplicatesBefore);
        assertThat(effects.count(payment)).isEqualTo(1);
    }

    private ConsumerRecord<String, String> recordOf(UUID eventId) {
        Topics.await(Duration.ofSeconds(20), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE event_id = ? AND published_at IS NOT NULL", Integer.class,
                eventId) == 1);
        return Topics.find(SampleApplication.TOPIC, eventId);
    }

    /** The record as it is: same key, payload and headers, as a relay failover would send it again. */
    private static void republish(ConsumerRecord<String, String> record) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, MessagingContainers.KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(record.topic(), null, record.key(), record.value(), record.headers()));
            producer.flush();
        }
    }

    private double duplicates() {
        var counter = meters.find("tpe.inbox.duplicates").counter();
        return counter == null ? 0 : counter.count();
    }

    private static PaymentId payment() {
        return new PaymentId(UUID.randomUUID());
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
