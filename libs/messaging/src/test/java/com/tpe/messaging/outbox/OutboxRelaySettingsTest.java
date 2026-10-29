package com.tpe.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.messaging.outbox.OutboxRelay.Settings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxRelaySettingsTest {

    private static final Settings DEFAULTS = Settings.defaults("csm-gateway");

    @Test
    void theDefaultsAreThoseOfTheSpec() {
        assertThat(DEFAULTS.lockName()).isEqualTo("csm-gateway-outbox");
        assertThat(DEFAULTS.lockSessionTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(DEFAULTS.heartbeatInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(DEFAULTS.takeoverRetry()).isEqualTo(Duration.ofSeconds(1));
        assertThat(DEFAULTS.stallAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aTakeoverRetryAsLongAsTheLockSessionTimeoutIsRejected() {
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), DEFAULTS.heartbeatInterval()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takeover-retry");
    }

    @Test
    void aHeartbeatIntervalAsLongAsTheLockSessionTimeoutIsRejected() {
        assertThatThrownBy(() -> with(DEFAULTS.takeoverRetry(), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat-interval");
    }

    private static Settings with(Duration takeoverRetry, Duration heartbeatInterval) {
        return new Settings(DEFAULTS.lockName(), DEFAULTS.visibilityWindow(), DEFAULTS.batchSize(),
                DEFAULTS.pollTimeout(), takeoverRetry, DEFAULTS.lockSessionTimeout(), heartbeatInterval,
                DEFAULTS.stallAfter(), DEFAULTS.minBackoff(), DEFAULTS.maxBackoff(), DEFAULTS.ackTimeout());
    }
}
