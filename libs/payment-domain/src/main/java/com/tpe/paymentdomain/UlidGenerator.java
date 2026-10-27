package com.tpe.paymentdomain;

import java.time.Clock;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Generates ULIDs: 48-bit millisecond timestamp and 80 random bits, 26 Crockford base32 characters. Monotonic:
 * within the same millisecond (or if the clock steps back) the previous random part is incremented, so values
 * from one generator are strictly increasing. Thread-safe.
 */
public final class UlidGenerator {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final long RANDOM_HIGH_MASK = 0xFFFFL;

    private final Clock clock;
    private final RandomGenerator random;

    private long lastMillis = Long.MIN_VALUE;
    private long randomHigh; // top 16 of the 80 random bits
    private long randomLow; // low 64 of the 80 random bits

    public UlidGenerator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public synchronized String next() {
        long millis = clock.millis();
        if (millis > lastMillis) {
            lastMillis = millis;
            randomHigh = random.nextLong() & RANDOM_HIGH_MASK;
            randomLow = random.nextLong();
        } else {
            increment();
        }
        return encode(lastMillis, randomHigh, randomLow);
    }

    private void increment() {
        randomLow++;
        if (randomLow == 0) {
            if (randomHigh == RANDOM_HIGH_MASK) {
                throw new IllegalStateException("ULID random part exhausted within one millisecond");
            }
            randomHigh++;
        }
    }

    private static String encode(long millis, long high, long low) {
        char[] chars = new char[26];
        for (int i = 0; i < 10; i++) {
            chars[i] = CROCKFORD[(int) (millis >>> (45 - 5 * i)) & 31];
        }
        for (int i = 0; i < 16; i++) {
            int shift = 75 - 5 * i;
            long bits;
            if (shift >= 64) {
                bits = high >>> (shift - 64);
            } else if (shift > 59) {
                bits = (low >>> shift) | (high << (64 - shift));
            } else {
                bits = low >>> shift;
            }
            chars[10 + i] = CROCKFORD[(int) bits & 31];
        }
        return new String(chars);
    }
}
