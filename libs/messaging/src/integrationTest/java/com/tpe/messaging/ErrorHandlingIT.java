package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.ContractJson;
import com.tpe.contracts.ContractViolationException;
import com.tpe.messaging.consumer.UnknownMessageTypeException;
import com.tpe.messaging.sample.SampleApplication;
import com.tpe.messaging.sample.SampleApplication.Accepting;
import com.tpe.messaging.sample.SampleApplication.OrderedEffects;
import com.tpe.messaging.sample.SampleApplication.RecordingDltHook;
import com.tpe.paymentdomain.PaymentId;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
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

/**
 * {@code TpeErrorHandler} (specs/01-shared-libraries.md §3.6): poison messages go to the DLT after 3 attempts and the
 * partition moves on; any other failure is retried in place until it goes away. Same context as
 * {@link OutboxToInboxIT}.
 */
@SpringBootTest(classes = SampleApplication.class, properties = {
    "tpe.messaging.enabled=true",
    "spring.application.name=sample-service"
})
class ErrorHandlingIT {

    private static final String DLT = SampleApplication.EVENTS + ".dlt";
    private static final ContractJson JSON = new ContractJson();

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        MessagingContainers.register(registry);
    }

    @Autowired
    OrderedEffects effects;

    @Autowired
    RecordingDltHook hook;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void MSG_AC_04_aMalformedPayloadGoesToTheDltAfter3AttemptsAndThePartitionMovesOn() {
        String key = UUID.randomUUID().toString();
        UUID poison = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        double dltBefore = dltMessages();

        send(key, poison, "AcceptCreditTransfer", "{not json");
        send(key, next, "AcceptCreditTransfer", valid());

        Topics.await(Duration.ofSeconds(30), () -> effects.applied(key).equals(List.of(next)));
        assertThat(effects.attempts(poison)).isEqualTo(3);

        ConsumerRecord<String, String> dead = Topics.find(DLT, poison);
        assertThat(dead.key()).isEqualTo(key);
        assertThat(dead.value()).isEqualTo("{not json");
        assertThat(Topics.header(dead, "tpe-dlt-exception")).isEqualTo(ContractViolationException.class.getName());
        assertThat(Topics.header(dead, "tpe-dlt-reason")).contains("MALFORMED");
        assertThat(Topics.header(dead, "tpe-dlt-original-partition")).matches("\\d+");
        assertThat(Topics.header(dead, "tpe-dlt-original-offset")).matches("\\d+");
        assertThat(Topics.header(dead, "kafka_dlt-exception-message")).isNull();
        assertThat(Topics.header(dead, "kafka_dlt-exception-stacktrace")).isNull();

        assertThat(dltMessages()).isEqualTo(dltBefore + 1);
        assertThat(hook.calls()).anySatisfy(call -> {
            assertThat(call.topic()).isEqualTo(SampleApplication.EVENTS);
            assertThat(call.key()).isEqualTo(key);
            assertThat(call.cause()).isInstanceOf(ContractViolationException.class);
        });
    }

    @Test
    void aContractViolationAndAnUnknownMessageTypeEndOnTheDlt() {
        String key = UUID.randomUUID().toString();
        UUID invalid = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID next = UUID.randomUUID();

        send(key, invalid, "AcceptCreditTransfer", "{}");
        send(key, unknown, "Bogus", valid());
        send(key, next, "AcceptCreditTransfer", valid());

        Topics.await(Duration.ofSeconds(30), () -> effects.applied(key).equals(List.of(next)));
        assertThat(Topics.header(Topics.find(DLT, invalid), "tpe-dlt-reason")).contains("REQUIRED");
        assertThat(Topics.header(Topics.find(DLT, unknown), "tpe-dlt-exception"))
                .isEqualTo(UnknownMessageTypeException.class.getName());
        assertThat(hook.calls()).filteredOn(call -> call.key().equals(key))
                .extracting(call -> call.cause().getClass().getName())
                .containsExactly(ContractViolationException.class.getName(),
                        UnknownMessageTypeException.class.getName());
    }

    @Test
    void MSG_AC_05_aDbFailureIsRetriedUntilTheDbIsBackWithoutDltAndInKeyOrder() {
        String key = UUID.randomUUID().toString();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        double dltBefore = dltMessages();

        jdbc.execute("ALTER TABLE sample_ordered_effects RENAME TO sample_ordered_effects_down");
        try {
            send(key, first, "AcceptCreditTransfer", valid());
            send(key, second, "AcceptCreditTransfer", valid());
            // more attempts than a poison message gets: the failure is retried, not dead-lettered
            Topics.await(Duration.ofSeconds(30), () -> effects.attempts(first) >= 5);
            assertThat(effects.attempts(second)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE sample_ordered_effects_down RENAME TO sample_ordered_effects");
        }

        Topics.await(Duration.ofSeconds(30), () -> effects.applied(key).size() == 2);
        assertThat(effects.applied(key)).containsExactly(first, second);
        assertThat(dltMessages()).isEqualTo(dltBefore);
        assertThat(hook.calls()).noneMatch(call -> call.key().equals(key));
    }

    private static String valid() {
        return JSON.write(Accepting.message(new PaymentId(UUID.randomUUID())));
    }

    /** A record as the relay would publish it, with any body and type. */
    private static void send(String key, UUID eventId, String messageType, String body) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, MessagingContainers.KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            var record = new ProducerRecord<>(SampleApplication.EVENTS, key, body);
            record.headers()
                    .add(MessageHeaders.EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8))
                    .add(MessageHeaders.MESSAGE_TYPE, messageType.getBytes(StandardCharsets.UTF_8))
                    .add(MessageHeaders.SCHEMA_VERSION, "1".getBytes(StandardCharsets.UTF_8));
            producer.send(record);
            producer.flush();
        }
    }

    private double dltMessages() {
        var counter = meters.find("tpe.dlt.messages").tag("topic", SampleApplication.EVENTS).counter();
        return counter == null ? 0 : counter.count();
    }
}
