package com.tpe.csmgateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A dead gateway's command partitions move on before the answer deadline (specs/15-csm-gateway.md §4). */
class CommandSessionBoundTest {

    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final String SESSION = CommandSessionBound.SESSION_TIMEOUT;

    @Test
    void aSessionShorterThanTheAnswerDeadlineIsAccepted() {
        assertThatCode(() -> CommandSessionBound.requireBelowAnswerDeadline(Map.of(SESSION, "3000"), DEADLINE))
                .doesNotThrowAnyException();
    }

    @Test
    void aSessionAsLongAsTheAnswerDeadlineIsRefused() {
        assertThatThrownBy(() -> CommandSessionBound.requireBelowAnswerDeadline(Map.of(SESSION, "5000"), DEADLINE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PT5S");
    }

    @Test
    void withoutASessionTheClientsDefaultOf45sIsRefused() {
        assertThatThrownBy(() -> CommandSessionBound.requireBelowAnswerDeadline(Map.of(), DEADLINE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PT45S");
    }
}
