package com.tpe.paymentorchestrator.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@code hold-give-up-margin < send-by-offset < deadline-offset} (specs/11-payment-orchestrator.md §7). */
class OutgoingTimingsTest {

    @Test
    void sendByAndDeadlineAreOffsetsFromAcceptance() {
        var timings = new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2));
        Instant accepted = Instant.parse("2026-11-13T10:00:00.250Z");

        assertThat(timings.sendBy(accepted)).isEqualTo(Instant.parse("2026-11-13T10:00:10.250Z"));
        assertThat(timings.deadline(accepted)).isEqualTo(Instant.parse("2026-11-13T10:00:20.250Z"));
    }

    @ParameterizedTest(name = "send-by {0}, deadline {1}, margin {2}")
    @CsvSource({
        "PT20S, PT20S, PT2S", // send_by not before the deadline
        "PT25S, PT20S, PT2S",
        "PT10S, PT20S, PT10S", // the hold is given up before it is tried
        "PT10S, PT20S, PT11S",
        "PT10S, PT20S, PT-1S"
    })
    void offsetsThatBreakTheOrderingAreRefused(Duration sendBy, Duration deadline, Duration margin) {
        assertThatThrownBy(() -> new OutgoingTimings(sendBy, deadline, margin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hold-give-up-margin");
    }
}
