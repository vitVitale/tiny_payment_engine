package com.tpe.systemtests.harness;

import com.tpe.invariantchecker.EventInvariants;
import com.tpe.messaging.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * A test consumer that records every record of some topics on the compose Kafka from the end as of {@link #start}: the
 * test consumer on {@code payments.events} (INV-7), the dead-letter topics (INV-9) and {@code csm.outbound.commands}
 * (no {@code pacs.008} for an on-us payment) of specs/20-testing-strategy.md §3. No consumer group, so it commits
 * nothing and every test JVM sees every record. Tests assert only on their own payments' records (20 §2).
 */
public final class TopicRecorder implements AutoCloseable {

    public static final String PAYMENTS_EVENTS = "payments.events";
    public static final String CSM_OUTBOUND_COMMANDS = "csm.outbound.commands";
    /** The dead-letter topics of deploy/kafka/topics.yml ({@code <topic>.dlt}, messaging {@code DltPublisher}). */
    public static final List<String> DEAD_LETTER_TOPICS = List.of("csm.outbound.commands.dlt",
            "csm.inbound.events.dlt", "payments.events.dlt", "accounts.events.dlt");

    /** One record as read: its topic, key, {@code tpe-message-type}, value, DLT reason if any, and when it was read. */
    public record Recorded(String topic, String key, String type, String value, String deadLetterReason,
            Instant readAt) {}

    private static final Clock CLOCK = Clock.systemUTC();

    private final KafkaConsumer<String, String> consumer;
    private final List<Recorded> records = new CopyOnWriteArrayList<>();
    private final Thread thread;
    private volatile RuntimeException failure;

    private TopicRecorder(String bootstrapServers) {
        this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"));
        this.thread = Thread.ofPlatform().daemon().name("topic-recorder").unstarted(this::run);
    }

    /** Positioned at the end of every partition of {@code topics} before it returns, then recording. */
    public static TopicRecorder start(String bootstrapServers, Collection<String> topics) {
        var started = new TopicRecorder(bootstrapServers);
        var partitions = new ArrayList<TopicPartition>();
        for (String topic : topics) {
            var infos = started.consumer.partitionsFor(topic, Duration.ofSeconds(15));
            if (infos == null || infos.isEmpty()) {
                started.consumer.close();
                throw new IllegalStateException(topic + " does not exist on " + bootstrapServers
                        + "; the compose stack creates it (deploy/kafka/topics.yml)");
            }
            infos.forEach(info -> partitions.add(new TopicPartition(topic, info.partition())));
        }
        started.consumer.assign(partitions);
        started.consumer.seekToEnd(partitions);
        // resolves the end offsets now: records of the run cannot slip in before the position is fixed
        partitions.forEach(partition -> started.consumer.position(partition, Duration.ofSeconds(15)));
        started.thread.start();
        return started;
    }

    /** The records of {@code topic} keyed {@code key}, in the order read. */
    public List<Recorded> records(String topic, String key) {
        return records.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).toList();
    }

    /** The records of {@code topic} whose value mentions {@code text}, e.g. a payment id inside a command. */
    public List<Recorded> mentioning(String topic, String text) {
        return records.stream().filter(r -> r.topic().equals(topic) && r.value() != null && r.value().contains(text))
                .toList();
    }

    /** The final events of {@code payments}, for INV-7. */
    public List<EventInvariants.FinalEvent> finalEvents(Collection<UUID> payments) {
        return payments.stream().flatMap(payment -> records(PAYMENTS_EVENTS, payment.toString()).stream()
                .map(record -> new EventInvariants.FinalEvent(payment, record.type()))).toList();
    }

    /** When the first final event of {@code payment} was read, if one was. */
    public Optional<Instant> firstFinalEventReadAt(UUID payment) {
        return records(PAYMENTS_EVENTS, payment.toString()).stream().map(Recorded::readAt).min(Instant::compareTo);
    }

    /** Every dead letter recorded so far: any is a breach of INV-9, whoever caused it. */
    public List<EventInvariants.DeadLetter> deadLetters() {
        return records.stream().filter(r -> DEAD_LETTER_TOPICS.contains(r.topic()))
                .map(r -> new EventInvariants.DeadLetter(r.topic(), r.key(), r.deadLetterReason())).toList();
    }

    /** Throws what stopped the recorder, if anything did. */
    public void assertHealthy() {
        if (failure != null) {
            throw new IllegalStateException("the topic recorder stopped", failure);
        }
    }

    private void run() {
        try {
            while (true) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    records.add(new Recorded(record.topic(), record.key(),
                            header(record.headers(), MessageHeaders.MESSAGE_TYPE), record.value(),
                            header(record.headers(), MessageHeaders.DLT_REASON), CLOCK.instant()));
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

    private static String header(Headers headers, String name) {
        var header = headers.lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws InterruptedException {
        consumer.wakeup();
        thread.join(Duration.ofSeconds(10));
    }
}
