package com.tpe.paymentdomain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that stands still until a test moves it (specs/00-engineering-baseline.md §6). Shipped in the
 * {@code payment-domain:tests} fixture jar; thread-safe, and zoned views share its time.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(Objects.requireNonNull(start, "start")), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        now.updateAndGet(instant -> instant.plus(duration));
    }

    public void set(Instant instant) {
        now.set(Objects.requireNonNull(instant, "instant"));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, Objects.requireNonNull(zone, "zone"));
    }
}
