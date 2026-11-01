package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

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
}
