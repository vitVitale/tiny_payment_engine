package com.tpe.csmsimulator.domain.scenarios;

import java.util.Arrays;
import java.util.Objects;

/**
 * What the CSM does once we accept an incoming payment in time (specs/17-csm-simulator.md §3.2, incoming; UC-06), as
 * the control API writes it.
 */
public enum IncomingScenario {
    /** Settles and confirms with a {@code pacs.002 ACSC}. */
    ACCEPT,
    /** Does not settle and answers {@code pacs.002 RJCT AB05} (UC-06 8a). */
    REJECT_AFTER_ACCP,
    /** Settles but sends no confirmation; an investigation finds it settled (UC-06 8b). */
    NO_CONFIRMATION;

    /** @throws IllegalArgumentException if {@code text} names no incoming scenario */
    public static IncomingScenario parse(String text) {
        Objects.requireNonNull(text, "text");
        return Arrays.stream(values()).filter(s -> s.name().equals(text)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not an incoming scenario: " + text + "; one of "
                        + Arrays.toString(values())));
    }
}
