package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HealthProbesIT extends LedgerStack {

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void answersProbeWithUpWithoutAToken(String path) {
        var response = http().get().uri(path).retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    /** What the node's agent scrapes, without a token (specs/26-observability.md §6.1, OBS-AC-01). */
    @Test
    void servesItsMetricsWithoutAToken() {
        var response = http().get().uri("/actuator/prometheus").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }
}
