package com.tpe.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.ContractJson;
import com.tpe.contracts.ContractViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOffExecution;

class TpeErrorHandlerTest {

    static Stream<Exception> nonRetryable() {
        return Stream.of(
                new DeserializationException("cannot deserialise", new byte[0], false, null),
                contractViolation(),
                new UnknownMessageTypeException("sample.events", "Bogus"));
    }

    static Stream<Exception> retryable() {
        return Stream.of(
                new DataAccessResourceFailureException("connection refused"),
                new OptimisticLockingFailureException("row changed"),
                new IllegalStateException("anything else"));
    }

    @ParameterizedTest
    @MethodSource("nonRetryable")
    void nonRetryableFailuresAreTriedThreeTimes100msApart(Exception failure) {
        assertThat(TpeErrorHandler.isNonRetryable(failure)).isTrue();
        assertThat(intervals(failure, 10)).containsExactly(100L, 100L);
    }

    @Test
    void aWrappedNonRetryableFailureIsNonRetryable() {
        var wrapped = new ListenerExecutionFailedException("listener failed", contractViolation());

        assertThat(TpeErrorHandler.isNonRetryable(wrapped)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("retryable")
    void otherFailuresAreRetriedIndefinitelyBackingOffFrom100msTo5s(Exception failure) {
        assertThat(TpeErrorHandler.isNonRetryable(failure)).isFalse();
        List<Long> intervals = intervals(failure, 1_000);

        assertThat(intervals).hasSize(1_000);
        assertThat(intervals.subList(0, 8)).containsExactly(100L, 200L, 400L, 800L, 1_600L, 3_200L, 5_000L, 5_000L);
        assertThat(intervals).allMatch(interval -> interval <= 5_000L);
    }

    /** The back-off intervals for {@code failure}, at most {@code limit}; fewer if the back-off stops. */
    private static List<Long> intervals(Exception failure, int limit) {
        BackOffExecution execution = TpeErrorHandler.backOffFor(failure).start();
        List<Long> intervals = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP && intervals.size() < limit;
                next = execution.nextBackOff()) {
            intervals.add(next);
        }
        return intervals;
    }

    static ContractViolationException contractViolation() {
        try {
            new ContractJson().read("{not json", AcceptCreditTransfer.class);
        } catch (ContractViolationException e) {
            return e;
        }
        throw new AssertionError("malformed JSON was read");
    }
}
