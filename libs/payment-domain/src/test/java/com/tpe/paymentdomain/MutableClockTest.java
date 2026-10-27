package com.tpe.paymentdomain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MutableClockTest {

    private static final Instant START = Instant.parse("2026-10-21T09:15:30.123Z");

    @Test
    void standsStillUntilMoved() {
        MutableClock clock = new MutableClock(START);

        assertThat(clock.instant()).isEqualTo(START);
        assertThat(clock.instant()).isEqualTo(START);
        assertThat(clock.millis()).isEqualTo(START.toEpochMilli());
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void advanceMovesForward() {
        MutableClock clock = new MutableClock(START);

        clock.advance(Duration.ofSeconds(20));
        clock.advance(Duration.ofMillis(5));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-21T09:15:50.128Z"));
    }

    @Test
    void setJumpsToAnInstant() {
        MutableClock clock = new MutableClock(START);

        clock.set(Instant.parse("2026-12-31T23:59:59Z"));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-12-31T23:59:59Z"));
    }

    @Test
    void zonedViewSharesTheTime() {
        MutableClock clock = new MutableClock(START);
        Clock berlin = clock.withZone(ZoneId.of("Europe/Berlin"));

        clock.advance(Duration.ofMinutes(1));

        assertThat(berlin.getZone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(berlin.instant()).isEqualTo(START.plusSeconds(60));
    }
}
