package com.tpe.messaging.consumer;

import com.tpe.contracts.ContractViolationException;
import java.util.List;
import java.util.Map;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The listener error handling of specs/01-shared-libraries.md §3.6. A consumer never gets stuck on a poison message
 * and never skips one it merely cannot process yet:
 * <ul>
 *   <li>non-retryable ({@link DeserializationException}, {@link ContractViolationException},
 *       {@link UnknownMessageTypeException}, also as a cause): 3 attempts 100 ms apart, then the recoverer, normally
 *       a {@link DltPublisher}, and the offset is committed;
 *   <li>everything else (DB unavailable, optimistic-lock conflict): retried in place, backing off exponentially from
 *       100 ms to 5 s, indefinitely. The partition is blocked on purpose: skipping would break per-key ordering.
 * </ul>
 */
public final class TpeErrorHandler extends DefaultErrorHandler {

    private static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
            DeserializationException.class, ContractViolationException.class, UnknownMessageTypeException.class);

    /** 3 attempts: the first delivery and 2 retries. */
    private static final BackOff NON_RETRYABLE_BACK_OFF = new FixedBackOff(100, 2);
    private static final BackOff RETRYABLE_BACK_OFF = retryableBackOff();

    public TpeErrorHandler(ConsumerRecordRecoverer recoverer) {
        super(recoverer, RETRYABLE_BACK_OFF);
        // every failure goes through the back-off; it alone decides between 3 attempts and retrying forever
        setClassifications(Map.of(), true);
        setBackOffFunction((record, exception) -> backOffFor(exception));
        setCommitRecovered(true);
    }

    static BackOff backOffFor(Exception exception) {
        return isNonRetryable(exception) ? NON_RETRYABLE_BACK_OFF : RETRYABLE_BACK_OFF;
    }

    static boolean isNonRetryable(Throwable exception) {
        return nonRetryableCause(exception) != null;
    }

    /**
     * The failure to report: the first non-retryable exception in the cause chain, else {@code exception} without
     * the container's {@link ListenerExecutionFailedException} wrapper.
     */
    static Exception cause(Exception exception) {
        Exception nonRetryable = nonRetryableCause(exception);
        if (nonRetryable != null) {
            return nonRetryable;
        }
        Exception unwrapped = exception;
        while (unwrapped instanceof ListenerExecutionFailedException && unwrapped.getCause() instanceof Exception c) {
            unwrapped = c;
        }
        return unwrapped;
    }

    private static Exception nonRetryableCause(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (Class<? extends Exception> type : NON_RETRYABLE) {
                if (type.isInstance(t)) {
                    return type.cast(t);
                }
            }
        }
        return null;
    }

    private static BackOff retryableBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(100, 2.0);
        backOff.setMaxInterval(5_000);
        return backOff;
    }
}
