package com.tpe.systemtests.harness;

import com.tpe.contracts.AccountEvent;
import com.tpe.contracts.Contract;
import com.tpe.contracts.ContractJson;
import com.tpe.messaging.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * The test consumer of {@code accounts.events} on the compose Kafka (specs/20-testing-strategy.md §4, E2E-A-01),
 * feeding an {@link AccountsProjection}. It reads every partition from the end as of {@link #start}, so it sees what
 * happens during the run and nothing older; no consumer group, so it commits nothing.
 */
public final class AccountsEventsConsumer implements AutoCloseable {

    static final String TOPIC = "accounts.events";

    /** The {@code tpe-message-type} header → record class (02 §1). */
    private static final Map<String, Class<? extends AccountEvent>> TYPES =
            Arrays.stream(AccountEvent.class.getPermittedSubclasses())
                    .map(type -> type.asSubclass(AccountEvent.class))
                    .collect(Collectors.toUnmodifiableMap(type -> type.getAnnotation(Contract.class).type(), t -> t));

    private final KafkaConsumer<String, String> consumer;
    private final AccountsProjection projection;
    private final ContractJson json = new ContractJson();
    private final Thread thread;
    private volatile RuntimeException failure;

    private AccountsEventsConsumer(String bootstrapServers, AccountsProjection projection) {
        this.projection = projection;
        this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"));
        this.thread = Thread.ofPlatform().daemon().name("accounts-events-consumer").unstarted(this::run);
    }

    /** Positioned at the end of every partition before it returns, then consuming in the background. */
    public static AccountsEventsConsumer start(String bootstrapServers, AccountsProjection projection) {
        var started = new AccountsEventsConsumer(bootstrapServers, projection);
        var infos = started.consumer.partitionsFor(TOPIC, Duration.ofSeconds(15));
        if (infos == null || infos.isEmpty()) {
            started.consumer.close();
            throw new IllegalStateException(TOPIC + " does not exist on " + bootstrapServers
                    + "; the compose stack creates it (deploy/kafka/topics.yml)");
        }
        var partitions = infos.stream().map(info -> new TopicPartition(TOPIC, info.partition())).toList();
        started.consumer.assign(partitions);
        started.consumer.seekToEnd(partitions);
        // resolves the end offsets now: events of the run cannot slip in before the position is fixed
        partitions.forEach(partition -> started.consumer.position(partition, Duration.ofSeconds(15)));
        started.thread.start();
        return started;
    }

    /** Throws what stopped the consumer, if anything did: an unreadable event fails the test, not silently. */
    public void assertHealthy() {
        if (failure != null) {
            throw new IllegalStateException("the accounts.events test consumer stopped", failure);
        }
    }

    private void run() {
        try {
            while (true) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    projection.apply(UUID.fromString(record.key()), event(record));
                }
            }
        } catch (WakeupException e) {
            // closed
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            consumer.close();
        }
    }

    private AccountEvent event(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return null;
        }
        var header = record.headers().lastHeader(MessageHeaders.MESSAGE_TYPE);
        String type = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        Class<? extends AccountEvent> recordClass = TYPES.get(type);
        if (recordClass == null) {
            throw new IllegalStateException("unexpected message type on " + TOPIC + ": " + type);
        }
        return json.read(record.value(), recordClass);
    }

    @Override
    public void close() throws InterruptedException {
        consumer.wakeup();
        thread.join(Duration.ofSeconds(10));
    }
}
