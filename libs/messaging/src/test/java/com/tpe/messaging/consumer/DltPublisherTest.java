package com.tpe.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.SendResult;

class DltPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> hookCalls = new ArrayList<>();

    private DltPublisher publisher;

    @BeforeEach
    void publisher() {
        when(template.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.completedFuture(mock(SendResult.class)));
        DltHook hook = (record, cause) -> hookCalls.add(record.topic() + "@" + record.offset() + " "
                + cause.getClass().getSimpleName());
        publisher = new DltPublisher(template, meters, List.of(hook));
    }

    @Test
    void publishesTheRecordToTheTopicsDltWithTheDltHeaders() {
        var record = new ConsumerRecord<>("sample.events", 2, 41L, "key-1", "{not json");
        var cause = TpeErrorHandlerTest.contractViolation();

        publisher.accept(record, null, new ListenerExecutionFailedException("listener failed", cause));

        ProducerRecord<Object, Object> published = published();
        assertThat(published.topic()).isEqualTo("sample.events.dlt");
        assertThat(published.partition()).isNull();
        assertThat(published.key()).isEqualTo("key-1");
        assertThat(published.value()).isEqualTo("{not json");
        assertThat(header(published, "tpe-dlt-reason")).isEqualTo(cause.getMessage());
        assertThat(header(published, "tpe-dlt-exception")).isEqualTo(
                "com.tpe.contracts.ContractViolationException");
        assertThat(header(published, "tpe-dlt-original-offset")).isEqualTo("41");
        assertThat(header(published, "tpe-dlt-original-partition")).isEqualTo("2");
    }

    @Test
    void everyPublishCountsOnTheSourceTopicAndCallsTheHooks() {
        publisher.accept(new ConsumerRecord<>("sample.events", 0, 1L, "k", "v"), null,
                new UnknownMessageTypeException("sample.events", "Bogus"));
        publisher.accept(new ConsumerRecord<>("sample.events", 0, 2L, "k", "v"), null,
                new UnknownMessageTypeException("sample.events", "Bogus"));
        publisher.accept(new ConsumerRecord<>("other.events", 0, 7L, "k", "v"), null,
                TpeErrorHandlerTest.contractViolation());

        assertThat(meters.get("tpe.dlt.messages").tag("topic", "sample.events").counter().count()).isEqualTo(2);
        assertThat(meters.get("tpe.dlt.messages").tag("topic", "other.events").counter().count()).isEqualTo(1);
        assertThat(hookCalls).containsExactly(
                "sample.events@1 UnknownMessageTypeException",
                "sample.events@2 UnknownMessageTypeException",
                "other.events@7 ContractViolationException");
    }

    @Test
    void aFailedPublishIsNotCountedAndThrows() {
        Mockito.reset(template);
        when(template.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> publisher.accept(new ConsumerRecord<>("sample.events", 0, 1L, "k", "v"), null,
                new UnknownMessageTypeException("sample.events", "Bogus")))
                .isInstanceOf(KafkaException.class);
        assertThat(meters.find("tpe.dlt.messages").counter()).isNull();
        assertThat(hookCalls).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<Object, Object> published() {
        ArgumentCaptor<ProducerRecord<Object, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(template).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
