package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OBS-AC-01 with a real payment (issue #126): the harness's processes run on the host, so the local Alloy finds them
 * in a Prometheus file-discovery list the harness keeps, one target per process, labelled as the compose module labels
 * a container (specs/26-observability.md §3).
 */
class AlloyTargetsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyProcessIsATargetOnTheDockerHostWithItsPodAndInstance() {
        var running = new LinkedHashMap<String, Integer>();
        running.put("payment-api", 51001);
        running.put("csm-gateway-b", 51002);

        JsonNode targets = JSON.readTree(AlloyTargets.render(running));

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).get("targets").get(0).asString()).isEqualTo("host.docker.internal:51001");
        assertThat(targets.get(0).get("labels").get("pod").asString()).isEqualTo("payment-api");
        assertThat(targets.get(0).get("labels").get("instance").asString()).isEqualTo("payment-api");
        assertThat(targets.get(0).get("labels").get("namespace").asString()).isEqualTo("tpe");
        assertThat(targets.get(1).get("targets").get(0).asString()).isEqualTo("host.docker.internal:51002");
        assertThat(targets.get(1).get("labels").get("pod").asString()).isEqualTo("csm-gateway-b");
    }

    @Test
    void withoutProcessesTheListIsEmpty() {
        assertThat(JSON.readTree(AlloyTargets.render(Map.of()))).isEmpty();
    }
}
