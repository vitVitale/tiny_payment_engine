package com.tpe.paymentdomain;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Generates time-ordered UUID v7 (RFC 9562): 48-bit millisecond timestamp, then random bits. Values of different
 * milliseconds sort by time; within one millisecond their order is random. Thread-safe if the random source is.
 */
public final class UuidV7Generator {

    private final Clock clock;
    private final RandomGenerator random;

    public UuidV7Generator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public UUID next() {
        long msb = (clock.millis() << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
