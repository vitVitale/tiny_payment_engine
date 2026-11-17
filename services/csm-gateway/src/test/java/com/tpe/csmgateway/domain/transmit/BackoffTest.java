package com.tpe.csmgateway.domain.transmit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** {@code 200 ms × 2^(attempts−1)}, capped at 1 s (specs/15-csm-gateway.md §6.5). */
class BackoffTest {

    @ParameterizedTest(name = "after attempt {0}: {1} ms")
    @CsvSource({"1, 200", "2, 400", "3, 800", "4, 1000", "5, 1000", "40, 1000", "2147483647, 1000"})
    void doublesFrom200MsAndStopsAtOneSecond(int attempts, long millis) {
        assertThat(Backoff.after(attempts)).isEqualTo(Duration.ofMillis(millis));
    }

    @Test
    void thereIsNoBackoffBeforeTheFirstAttempt() {
        assertThatThrownBy(() -> Backoff.after(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
