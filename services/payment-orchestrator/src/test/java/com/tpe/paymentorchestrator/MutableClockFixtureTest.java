package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The {@code payment-domain:tests} fixture jar is usable from a service (specs/01-shared-libraries.md §1.3). */
class MutableClockFixtureTest {

    @Test
    void serviceTestsCanDriveTimeWithMutableClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-21T09:15:30Z"));
        Clock injected = clock;

        clock.advance(Duration.ofSeconds(20));

        assertThat(injected.instant()).isEqualTo(Instant.parse("2026-10-21T09:15:50Z"));
    }
}
