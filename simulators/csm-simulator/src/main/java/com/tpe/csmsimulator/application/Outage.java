package com.tpe.csmsimulator.application;

import java.time.Duration;
import java.util.Objects;

/**
 * The state of the CSM transport (specs/17-csm-simulator.md §3.5, {@code POST /sim/v1/outage}): {@code DOWN} answers
 * {@code 503} and discards the message, {@code RESET} closes the connection without a response and discards it,
 * {@code SLOW} processes it {@code delay} later, {@code UP} is normal service.
 */
public record Outage(Mode mode, Duration delay) {

    public enum Mode { UP, DOWN, RESET, SLOW }

    public static final Outage NONE = new Outage(Mode.UP, Duration.ZERO);

    public Outage {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay is negative");
        }
        if (mode != Mode.SLOW) {
            delay = Duration.ZERO;
        }
    }

    /** Whether a message reaching the transport is discarded without being received. */
    public boolean discards() {
        return mode == Mode.DOWN || mode == Mode.RESET;
    }
}
