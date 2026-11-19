package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.systemtests.harness.ServiceJar;
import com.tpe.systemtests.harness.ServiceProfile;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The compose file's profiles (specs/26-observability.md §13, issue #19): without a profile the stack is the
 * infrastructure of 00 §13 only; everything of the observability stack is behind {@code observability}, the Toxiproxy
 * the system tests produce through (specs/20-testing-strategy.md §4 E2E-I-06, §5 CH-03) behind {@code chaos}.
 */
class ComposeProfilesTest {

    @Test
    void withoutAProfileOnlyTheInfrastructureStartsAndTheObservabilityStackIsBehindItsProfile() throws IOException {
        Map<String, Map<String, Object>> services = services();

        assertThat(namesWhere(services, false)).containsExactlyInAnyOrder("postgres", "kafka", "oauth2");
        assertThat(namesWhere(services, true)).containsExactlyInAnyOrder(
                "alloy", "mimir", "loki", "tempo", "grafana", "observability-sample", "toxiproxy");
        assertThat(services.entrySet()).filteredOn(entry -> entry.getValue().containsKey("profiles"))
                .allSatisfy(entry -> assertThat(entry.getValue().get("profiles")).isEqualTo(
                        List.of(entry.getKey().equals("toxiproxy") ? "chaos" : "observability")));
    }

    /**
     * CH-03 blocks the orchestrators' produce, not their consumption: Kafka advertises a listener of its own at the
     * proxy's port, so a client that bootstraps there keeps talking through the proxy, and the proxy's ports are
     * fixed, as the advertised address must be.
     */
    @Test
    @SuppressWarnings("unchecked")
    void kafkaAdvertisesAChaosListenerAtTheToxiproxyPort() throws IOException {
        Map<String, Map<String, Object>> services = services();
        Map<String, Object> kafka = (Map<String, Object>) services.get("kafka").get("environment");

        assertThat(kafka.get("KAFKA_LISTENERS").toString()).contains("CHAOS://:9094");
        assertThat(kafka.get("KAFKA_ADVERTISED_LISTENERS").toString()).contains("CHAOS://localhost:19094");
        assertThat(kafka.get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP").toString()).contains("CHAOS:PLAINTEXT");
        assertThat((List<Object>) services.get("toxiproxy").get("ports")).contains("8474:8474", "19094:19094");
    }

    /**
     * CH-04 cuts the gateways' consumption off, not their produce, nor the orchestrators': a second listener of its own,
     * advertised at the second proxy's fixed port, for the gateways' consumers only.
     */
    @Test
    @SuppressWarnings("unchecked")
    void kafkaAdvertisesAConsumeListenerAtTheSecondToxiproxyPort() throws IOException {
        Map<String, Map<String, Object>> services = services();
        Map<String, Object> kafka = (Map<String, Object>) services.get("kafka").get("environment");

        assertThat(kafka.get("KAFKA_LISTENERS").toString()).contains("CHAOS_CONSUME://:9095");
        assertThat(kafka.get("KAFKA_ADVERTISED_LISTENERS").toString()).contains("CHAOS_CONSUME://localhost:19095");
        assertThat(kafka.get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP").toString())
                .contains("CHAOS_CONSUME:PLAINTEXT");
        assertThat((List<Object>) services.get("toxiproxy").get("ports")).contains("19095:19095");
    }

    /** The broker allows csm-gateway's shortest consumer session, the systemtest one (specs/15-csm-gateway.md §4). */
    @Test
    @SuppressWarnings("unchecked")
    void kafkaAllowsTheGatewaysSessionShorterThanTheAnswerDeadline() throws IOException {
        Map<String, Object> kafka = (Map<String, Object>) services().get("kafka").get("environment");
        Duration minimum = Duration.ofMillis(
                Long.parseLong(kafka.get("KAFKA_GROUP_MIN_SESSION_TIMEOUT_MS").toString()));

        assertThat(minimum).isLessThanOrEqualTo(ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY)
                .duration("spring.kafka.consumer.properties.session.timeout.ms").orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void grafanaAndOtlpUseThePortsOfTheSpec() throws IOException {
        Map<String, Map<String, Object>> services = services();

        assertThat(services.get("grafana").get("ports")).isEqualTo(List.of("3000:3000"));
        assertThat((List<Object>) services.get("alloy").get("ports")).contains("4317:4317", "4318:4318");
    }

    private static List<String> namesWhere(Map<String, Map<String, Object>> services, boolean profiled) {
        return services.entrySet().stream().filter(entry -> entry.getValue().containsKey("profiles") == profiled)
                .map(Map.Entry::getKey).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() throws IOException {
        Map<String, Object> compose = new Yaml().load(Repository.read("deploy/docker-compose.yml"));
        return (Map<String, Map<String, Object>>) compose.get("services");
    }
}
