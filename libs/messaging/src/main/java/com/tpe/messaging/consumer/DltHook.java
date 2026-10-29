package com.tpe.messaging.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Lets a service react to a record sent to its DLT (specs/01-shared-libraries.md §3.6), e.g. the orchestrator opens
 * an exception case for {@code csm.*} DLTs. Register one as a bean; every hook is called after each DLT publish, on
 * the listener thread, before the source offset is committed. A hook that throws makes the record fail again, so it
 * may be published to the DLT and reach the hooks more than once: hooks must be idempotent.
 */
@FunctionalInterface
public interface DltHook {

    /**
     * @param record the source record, as consumed
     * @param cause the non-retryable failure, unwrapped from the listener container's exception
     */
    void onDeadLetter(ConsumerRecord<?, ?> record, Exception cause);
}
