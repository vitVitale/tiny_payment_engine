package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundsTest {

    @Test
    void theEventDeliveryBoundFollowsTheRelaySettingsOfTheLastProfileThatSetsThem() {
        var defaults = Bounds.eventDelivery(new ServiceProfile(List.of(Map.of())));
        var profile = new ServiceProfile(List.of(
                Map.of("tpe", Map.of("messaging", Map.of("outbox", Map.of("takeover-retry", "2s")))),
                Map.of("tpe", Map.of("messaging", Map.of("outbox", Map.of("takeover-retry", "500ms",
                        "visibility-window", "PT0.12S"))))));

        // defaults: takeover 1 s, window 20 ms; the overrides: 500 ms and 120 ms
        assertThat(Bounds.eventDelivery(profile)).isEqualTo(defaults.minusMillis(500).plusMillis(100));
    }

    @Test
    void theLedgerProfileIsReadFromTheServicesSources() {
        assertThat(ServiceProfile.ledger().duration("tpe.ledger.snapshot.interval")).contains(Duration.ofSeconds(5));
        assertThat(ServiceProfile.ledger().duration("tpe.ledger.idempotency.retention")).contains(Duration.ofDays(30));
    }
}
