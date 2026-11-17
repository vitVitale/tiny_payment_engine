package com.tpe.csmgateway.domain.transmit;

import java.time.Duration;

/** How long a failed transmission waits: {@code 200 ms × 2^(attempts−1)}, capped at 1 s (specs/15-csm-gateway.md §6.5). */
public final class Backoff {

    private static final long FIRST_MILLIS = 200;
    private static final long CAP_MILLIS = 1_000;

    private Backoff() {}

    /** @param attempts the attempts made so far, the failed one included */
    public static Duration after(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("no attempt made yet: " + attempts);
        }
        // 200 × 2^3 already passes the cap; a larger shift would overflow
        long millis = attempts > 3 ? CAP_MILLIS : Math.min(FIRST_MILLIS << (attempts - 1), CAP_MILLIS);
        return Duration.ofMillis(millis);
    }
}
