package com.tpe.messaging.consumer;

import com.tpe.messaging.MessageHeaders;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

/**
 * Publishes a record that failed for good to {@code <topic>.dlt} (specs/01-shared-libraries.md §3.6, 02 §2): same
 * key, value and headers, any partition (a DLT has one), plus the {@code tpe-dlt-*} headers of
 * {@link MessageHeaders} and Spring's {@code kafka_dlt-*} ones without exception message and stack trace (a
 * deserialiser's message may quote input values, specs/26-observability.md §4.4). Then increments
 * {@code tpe.dlt.messages{topic}} with the source topic and calls the {@link DltHook}s. A failed publish throws, so
 * the record is not committed and fails again.
 */
public final class DltPublisher implements ConsumerAwareRecordRecoverer, AutoCloseable {

    static final String SUFFIX = ".dlt";

    private final DeadLetterPublishingRecoverer publisher;
    private final MeterRegistry registry;
    private final List<DltHook> hooks;
    private final AutoCloseable onClose;

    DltPublisher(KafkaOperations<?, ?> template, MeterRegistry registry, List<DltHook> hooks) {
        this(template, registry, hooks, () -> { });
    }

    private DltPublisher(KafkaOperations<?, ?> template, MeterRegistry registry, List<DltHook> hooks,
            AutoCloseable onClose) {
        this.publisher = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + SUFFIX, -1));
        this.publisher.setHeadersFunction(DltPublisher::dltHeaders);
        this.publisher.excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
        this.registry = registry;
        this.hooks = List.copyOf(hooks);
        this.onClose = onClose;
    }

    /**
     * With its own producer for {@code bootstrapServers}, closed by {@link #close}. Keys and values are published as
     * consumed: text, or the raw bytes of a record that failed deserialisation.
     */
    public static DltPublisher create(String bootstrapServers, MeterRegistry registry, List<DltHook> hooks) {
        Map<Class<?>, Serializer<?>> serializers = Map.of(
                String.class, new StringSerializer(), byte[].class, new ByteArraySerializer());
        var producers = new DefaultKafkaProducerFactory<Object, Object>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ProducerConfig.ACKS_CONFIG, "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                new DelegatingByTypeSerializer(serializers), new DelegatingByTypeSerializer(serializers));
        return new DltPublisher(new KafkaTemplate<>(producers), registry, hooks, producers::destroy);
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        Exception cause = TpeErrorHandler.cause(exception);
        publisher.accept(record, consumer, cause);
        Counter.builder("tpe.dlt.messages")
                .description("Records published to a DLT, by source topic")
                .tag("topic", record.topic())
                .register(registry)
                .increment();
        hooks.forEach(hook -> hook.onDeadLetter(record, cause));
    }

    @Override
    public void close() throws Exception {
        onClose.close();
    }

    /** {@code cause} is already unwrapped by {@link #accept}. */
    private static RecordHeaders dltHeaders(ConsumerRecord<?, ?> record, Exception cause) {
        var headers = new RecordHeaders();
        headers.add(MessageHeaders.DLT_REASON, utf8(String.valueOf(cause.getMessage())));
        headers.add(MessageHeaders.DLT_EXCEPTION, utf8(cause.getClass().getName()));
        headers.add(MessageHeaders.DLT_ORIGINAL_OFFSET, utf8(Long.toString(record.offset())));
        headers.add(MessageHeaders.DLT_ORIGINAL_PARTITION, utf8(Integer.toString(record.partition())));
        return headers;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
