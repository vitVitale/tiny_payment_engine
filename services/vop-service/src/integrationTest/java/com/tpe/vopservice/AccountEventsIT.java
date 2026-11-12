package com.tpe.vopservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AccountKind;
import com.tpe.contracts.AccountOpened;
import com.tpe.contracts.AccountSnapshot;
import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.ContractJson;
import com.tpe.contracts.Holder;
import com.tpe.contracts.HolderType;
import com.tpe.contracts.Message;
import com.tpe.messaging.MessageHeaders;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code accounts.events} through Kafka into the projection (specs/13-vop-service.md §4): the listener of group
 * {@code vop-service} reads a snapshot into {@code account_holders} and a tombstone out of it, as ledger-service's relay
 * publishes them (headers of specs/01-shared-libraries.md §3.4).
 */
class AccountEventsIT extends VopStack {

    private static final ContractJson JSON = new ContractJson();
    private static final Instant OPENED_AT = Instant.parse("2026-11-02T09:15:00.123Z");

    @Autowired
    JdbcClient jdbc;

    @Test
    void aSnapshotOnTheTopicReachesTheProjectionAndItsTombstoneRemovesIt() {
        UUID accountId = UUID.randomUUID();

        publish(accountId.toString(), opened(accountId, "DE89370400440532013000", "Dr. Juergen Mueller"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(normalisedName(accountId)).contains("juergen mueller"));

        publish(accountId.toString(), null);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(normalisedName(accountId)).isEmpty());
    }

    private Optional<String> normalisedName(UUID accountId) {
        return jdbc.sql("SELECT normalised_name FROM account_holders WHERE account_id = ?").param(accountId)
                .query(String.class).optional();
    }

    private static AccountOpened opened(UUID accountId, String iban, String name) {
        return new AccountOpened(accountId, 1L, OPENED_AT, new AccountSnapshot(new Iban(iban), "CUST-000123",
                AccountStatus.OPEN, List.of(), AccountKind.CUSTOMER,
                new Holder(HolderType.PERSON, new PartyName(name), LocalDate.parse("1985-04-12"), "DE", null),
                OPENED_AT, null));
    }

    /** As the relay sends it; a {@code null} message is a tombstone, which carries no type or version. */
    private static void publish(String key, Message message) {
        var record = new ProducerRecord<>("accounts.events", key, message == null ? null : JSON.write(message));
        record.headers().add(MessageHeaders.EVENT_ID, bytes(UUID.randomUUID().toString()));
        if (message != null) {
            var contract = Message.contractOf(message.getClass());
            record.headers().add(MessageHeaders.MESSAGE_TYPE, bytes(contract.type()));
            record.headers().add(MessageHeaders.SCHEMA_VERSION, bytes(Integer.toString(contract.version())));
        }
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(record);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
