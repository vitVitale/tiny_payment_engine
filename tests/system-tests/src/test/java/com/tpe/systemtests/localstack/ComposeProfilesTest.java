package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The compose file's profiles (specs/26-observability.md §13, issue #19): without a profile the stack is the
 * infrastructure of 00 §13 only; everything of the observability stack is behind {@code observability}.
 */
class ComposeProfilesTest {

    @Test
    void withoutAProfileOnlyTheInfrastructureStartsAndTheObservabilityStackIsBehindItsProfile() throws IOException {
        Map<String, Map<String, Object>> services = services();

        assertThat(namesWhere(services, false)).containsExactlyInAnyOrder("postgres", "kafka", "oauth2");
        assertThat(namesWhere(services, true))
                .containsExactlyInAnyOrder("alloy", "mimir", "loki", "tempo", "grafana", "observability-sample");
        assertThat(services.values()).filteredOn(service -> service.containsKey("profiles"))
                .allSatisfy(service -> assertThat(service.get("profiles")).isEqualTo(List.of("observability")));
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
