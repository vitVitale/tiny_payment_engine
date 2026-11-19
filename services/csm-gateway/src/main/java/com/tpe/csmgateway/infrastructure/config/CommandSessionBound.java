package com.tpe.csmgateway.infrastructure.config;

import java.time.Duration;
import java.util.Map;

/**
 * The gateway's consumer session on {@code csm.outbound.commands} (specs/15-csm-gateway.md §4): a gateway that
 * dies without leaving the group keeps its partitions, and the verdicts on them, until its session times out, so the
 * session must end before an incoming payment's answer deadline, or those payments are answered by {@code DEADLINE}.
 */
final class CommandSessionBound {

    static final String SESSION_TIMEOUT = "session.timeout.ms";
    /** The Kafka client's default without {@value #SESSION_TIMEOUT}. */
    static final Duration CLIENT_DEFAULT = Duration.ofSeconds(45);

    private CommandSessionBound() {}

    /**
     * Fails unless the session of the consumer's client properties (common and consumer ones merged, as the consumer
     * gets them) is shorter than {@code answerDeadlineOffset}.
     */
    static void requireBelowAnswerDeadline(Map<String, ?> consumerProperties, Duration answerDeadlineOffset) {
        Object configured = consumerProperties.get(SESSION_TIMEOUT);
        Duration session = configured == null
                ? CLIENT_DEFAULT
                : Duration.ofMillis(Long.parseLong(configured.toString().strip()));
        if (session.compareTo(answerDeadlineOffset) >= 0) {
            throw new IllegalStateException("spring.kafka.consumer.properties." + SESSION_TIMEOUT + " is " + session
                    + ", not shorter than tpe.csm.answer-deadline-offset " + answerDeadlineOffset
                    + ": a dead gateway's verdicts would wait past the answer deadline (specs/15-csm-gateway.md §4)");
        }
    }
}
