package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.systemtests.harness.ConfigurationConsistency.CallerTimeout;
import com.tpe.systemtests.harness.ConfigurationConsistency.TopicRetention;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The configuration-consistency gate of specs/20-testing-strategy.md §1 over every service's default configuration
 * (what production runs): inbox retention above the retention of every consumed {@code delete} topic (01 §3.1), and
 * every caller timeout of 25 §7 above the callee's highest-percentile SLO threshold (25 §5). Each rule also runs on a
 * configuration seeded with exactly its violation, so a check that passes everything fails here.
 */
class ConfigurationConsistencyTest {

    private static final TopicRetention WEEK_TOPIC =
            new TopicRetention("csm.inbound.events", Duration.ofDays(7), "delete", Set.of("payment-orchestrator"));

    @Test
    void everyInboxOutlivesTheDeleteTopicsItConsumes() {
        assertThat(ConfigurationConsistency.inboxViolations(
                ConfigurationConsistency.topicsFile(), ConfigurationConsistency::defaultProfile)).isEmpty();
    }

    @Test
    void anInboxNoLongerThanAConsumedTopicFails() {
        var sameAsTopic = profile(Map.of("tpe", Map.of("messaging", Map.of("inbox", Map.of("retention", "7d")))));

        assertThat(ConfigurationConsistency.inboxViolations(List.of(WEEK_TOPIC), service -> Optional.of(sameAsTopic)))
                .singleElement().asString()
                .contains("payment-orchestrator", "csm.inbound.events");
    }

    @Test
    void theLibraryDefaultCountsWhenAServiceSetsNoInboxRetention() {
        var nineDayTopic = new TopicRetention("t", Duration.ofDays(9), "delete", Set.of("payment-orchestrator"));

        assertThat(ConfigurationConsistency.inboxViolations(List.of(nineDayTopic), service -> Optional.of(profile(Map.of()))))
                .as("the 8 d default of the messaging lib is below a 9 d topic").hasSize(1);
        assertThat(ConfigurationConsistency.inboxViolations(List.of(WEEK_TOPIC), service -> Optional.of(profile(Map.of()))))
                .as("the 8 d default is above a 7 d topic").isEmpty();
    }

    @Test
    void compactedTopicsAndConsumersOutsideTheRepositoryAreSkipped() {
        var compacted = new TopicRetention("accounts.events", Duration.ofDays(1), "compact", Set.of("vop-service"));
        var opsOnly = new TopicRetention("x.dlt", Duration.ofDays(30), "delete", Set.of("ops-dlt"));

        assertThat(ConfigurationConsistency.inboxViolations(List.of(compacted, opsOnly),
                ConfigurationConsistency::defaultProfile)).isEmpty();
    }

    @Test
    void aConsumerWithoutConfigurationFails() {
        var misspelt = new TopicRetention("csm.inbound.events", Duration.ofDays(7), "delete",
                Set.of("payment-orchestrater"));

        assertThat(ConfigurationConsistency.inboxViolations(List.of(misspelt),
                ConfigurationConsistency::defaultProfile)).singleElement().asString()
                .contains("payment-orchestrater", "no configuration");
    }

    @Test
    void everyCallerTimeoutIsAboveTheCalleesHighestPercentileThreshold() {
        assertThat(ConfigurationConsistency.timeoutViolations(
                ConfigurationConsistency.CALLER_TIMEOUTS, ConfigurationConsistency::thresholdOf,
                ConfigurationConsistency::defaultProfile)).isEmpty();
    }

    @Test
    void theCalleeThresholdIsTheHighestOfItsSloRow() {
        assertThat(ConfigurationConsistency.thresholdOf("SLO-RSK-02")).isEqualTo(Duration.ofMillis(250));
        assertThat(ConfigurationConsistency.thresholdOf("SLO-VOP-02")).isEqualTo(Duration.ofMillis(5200));
        assertThat(ConfigurationConsistency.thresholdOf("SLO-LED-02")).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void aTimeoutAtTheCalleesThresholdFails() {
        var risk = new CallerTimeout("payment-orchestrator", "tpe.clients.risk.timeout.incoming", "SLO-RSK-02");
        var atThreshold = profile(Map.of("tpe", Map.of("clients", Map.of("risk", Map.of("timeout",
                Map.of("incoming", "250ms"))))));

        assertThat(ConfigurationConsistency.timeoutViolations(List.of(risk), slo -> Duration.ofMillis(250),
                service -> Optional.of(atThreshold))).singleElement().asString()
                .contains("tpe.clients.risk.timeout.incoming", "SLO-RSK-02");
    }

    @Test
    void anUnsetTimeoutFails() {
        var ledger = new CallerTimeout("payment-orchestrator", "tpe.clients.ledger.timeout", "SLO-LED-02");

        assertThat(ConfigurationConsistency.timeoutViolations(List.of(ledger), slo -> Duration.ofMillis(200),
                service -> Optional.of(profile(Map.of())))).singleElement().asString().contains("not set");
    }

    private static ServiceProfile profile(Map<String, Object> application) {
        return new ServiceProfile(List.of(application));
    }
}
