package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.ContractJson;
import com.tpe.messaging.outbox.JdbcOutboxWriter;
import com.tpe.messaging.sample.SampleApplication.Accepting;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox rows for {@code keys} keys on one topic, written round-robin, one transaction each, as the business would:
 * successive transactions per key. Remembers each key's insert order, to check what the consumer sees.
 */
public final class OutboxLoad implements AutoCloseable {

    private final DataSource dataSource;
    private final String topic;
    private final List<String> keys;
    private final JdbcOutboxWriter writer;
    private final TransactionTemplate transactions;
    private final Map<String, List<UUID>> written = new LinkedHashMap<>();

    private volatile boolean running;
    private Thread thread;

    public OutboxLoad(DataSource dataSource, String topic, int keys, String producer) {
        this.dataSource = dataSource;
        this.topic = topic;
        this.keys = IntStream.range(0, keys).mapToObj(i -> UUID.randomUUID().toString()).toList();
        this.writer = new JdbcOutboxWriter(new JdbcTemplate(dataSource), new ContractJson(),
                new UuidV7Generator(Clock.systemUTC(), new SecureRandom()), Clock.systemUTC(), producer);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.keys.forEach(key -> written.put(key, new ArrayList<>()));
    }

    /** Writes {@code rows} rows now, round-robin over the keys. */
    public void write(int rows) {
        for (int i = 0; i < rows; i++) {
            writeOne(keys.get(i % keys.size()));
        }
    }

    /** Writes in the background, one row about every {@code pause}, until {@link #close}. */
    public OutboxLoad startWriting(Duration pause) {
        running = true;
        thread = Thread.ofPlatform().name("outbox-load").daemon().start(() -> {
            for (int i = 0; running; i++) {
                writeOne(keys.get(i % keys.size()));
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        return this;
    }

    /** Stops the background writer. */
    @Override
    public void close() {
        running = false;
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized int size() {
        return written.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Waits until every written row is published, then checks the topic: every row arrived (no loss) and, per key,
     * the first copies arrive in insert order. Later copies are duplicates, which consumers drop.
     */
    public void assertDeliveredInKeyOrder(Duration timeout) {
        close();
        Map<String, List<UUID>> expected = snapshot();
        Set<UUID> all = new LinkedHashSet<>();
        expected.values().forEach(all::addAll);
        Topics.await(timeout, () -> unpublished(all) == 0);

        List<ConsumerRecord<String, String>> records = Topics.read(topic,
                consumed -> firstCopies(consumed).values().stream().mapToInt(Set::size).sum() >= all.size());

        Map<String, Set<UUID>> first = firstCopies(records);
        assertThat(first.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((key, ids) -> assertThat(first.get(key)).as("first copies of key %s", key)
                .containsExactlyElementsOf(ids));
    }

    private static Map<String, Set<UUID>> firstCopies(List<ConsumerRecord<String, String>> records) {
        Map<String, Set<UUID>> first = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            first.computeIfAbsent(record.key(), key -> new LinkedHashSet<>())
                    .add(UUID.fromString(Topics.header(record, MessageHeaders.EVENT_ID)));
        }
        return first;
    }

    private void writeOne(String key) {
        PaymentId payment = new PaymentId(UUID.randomUUID());
        UUID eventId = transactions.execute(status -> writer.write(topic, key, Accepting.message(payment)));
        synchronized (this) {
            written.get(key).add(eventId);
        }
    }

    private synchronized Map<String, List<UUID>> snapshot() {
        Map<String, List<UUID>> copy = new LinkedHashMap<>();
        written.forEach((key, ids) -> copy.put(key, List.copyOf(ids)));
        return copy;
    }

    private int unpublished(Set<UUID> eventIds) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL AND event_id = ANY(?::uuid[])",
                Integer.class, (Object) eventIds.stream().map(UUID::toString).toArray(String[]::new));
    }
}
