package com.tpe.paymentdomain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    @Test
    void isVersion7WithTheRfcVariant() {
        UUID uuid = new UuidV7Generator(new MutableClock(Instant.parse("2026-10-21T09:15:30Z")), new SplittableRandom(1)).next();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void startsWithTheUnixMillisecondTimestamp() {
        // test vector of RFC 9562 appendix A.6: 2022-02-22 19:22:22 UTC → 017f22e2-79b0-7...
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(0x017F22E279B0L));

        UUID uuid = new UuidV7Generator(clock, new SplittableRandom(1)).next();

        assertThat(uuid.toString()).startsWith("017f22e2-79b0-7");
    }

    @Test
    void valuesAreOrderedByTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-21T09:15:30Z"));
        UuidV7Generator generator = new UuidV7Generator(clock, new SplittableRandom(5));
        List<String> uuids = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            uuids.add(generator.next().toString());
            clock.advance(Duration.ofMillis(1));
        }

        assertThat(uuids).isSortedAccordingTo(String::compareTo).doesNotHaveDuplicates();
    }

    @Test
    void isReproducibleWithAFixedClockAndRandomSource() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-21T09:15:30Z"));

        assertThat(new UuidV7Generator(clock, new SplittableRandom(42)).next())
                .isEqualTo(new UuidV7Generator(clock, new SplittableRandom(42)).next());
    }
}
