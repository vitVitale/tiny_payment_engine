package com.tpe.paymentorchestrator.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Back-off, remote read timeouts and stuck actions of the saga runner (specs/11-payment-orchestrator.md §6.3). */
class RunnerSettingsTest {

    private static final RunnerSettings SETTINGS = new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30),
            256, Duration.ofMillis(100), Duration.ofMinutes(10));

    @ParameterizedTest(name = "attempt {0} -> {1}")
    @CsvSource({"1, PT0.1S", "2, PT0.2S", "3, PT0.4S", "4, PT0.8S", "9, PT25.6S", "10, PT30S", "64, PT30S"})
    void theBackOffDoublesFrom100msUpToTheCap(int attempts, Duration backoff) {
        assertThat(SETTINGS.backoff(attempts)).isEqualTo(backoff);
    }

    @Test
    void theReadTimeoutIsTheConfiguredOneWhileTheDeadlineIsFar() {
        Instant now = Instant.parse("2026-11-13T10:00:00Z");

        assertThat(SETTINGS.readTimeout(Duration.ofMillis(500), now.plusSeconds(10), now))
                .contains(Duration.ofMillis(500));
    }

    @Test
    void nearTheDeadlineTheReadTimeoutShrinksToWhatIsLeftBeforeTheMargin() {
        Instant now = Instant.parse("2026-11-13T10:00:00Z");

        assertThat(SETTINGS.readTimeout(Duration.ofMillis(500), now.plusMillis(400), now))
                .contains(Duration.ofMillis(300));
    }

    @Test
    void noTimeIsLeftAtOrAfterTheMargin() {
        Instant now = Instant.parse("2026-11-13T10:00:00Z");

        assertThat(SETTINGS.readTimeout(Duration.ofMillis(500), now.plusMillis(100), now)).isEmpty();
        assertThat(SETTINGS.readTimeout(Duration.ofMillis(500), now.minusSeconds(1), now)).isEmpty();
    }

    @Test
    void withoutADeadlineTheConfiguredTimeoutHolds() {
        Instant now = Instant.parse("2026-11-13T10:00:00Z");

        assertThat(SETTINGS.readTimeout(Duration.ofSeconds(1), null, now)).contains(Duration.ofSeconds(1));
    }

    @Test
    void aConcurrencyBelowOneIsRefused() {
        assertThatThrownBy(() -> new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 0,
                Duration.ofMillis(100), Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anActionPendingForStuckAfterIsStuck() {
        Instant since = Instant.parse("2026-11-13T10:00:00Z");

        assertThat(SETTINGS.stuck(since, since.plus(Duration.ofMinutes(10)).minusMillis(1))).isFalse();
        assertThat(SETTINGS.stuck(since, since.plus(Duration.ofMinutes(10)))).isTrue();
    }
}
