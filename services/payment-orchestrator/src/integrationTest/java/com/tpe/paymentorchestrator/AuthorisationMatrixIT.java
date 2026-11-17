package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authorisation matrix, generated from the service's own OpenAPI document (specs/20-testing-strategy.md §1,
 * 00 §12.1): every operation that needs the staff JWT answers {@code 401 UNAUTHENTICATED} without a token and
 * {@code 403 FORBIDDEN} without {@code BACKOFFICE_OPERATOR}; every other operation is an {@code @InternalApi} one that
 * takes no token until M5 (ADR-012). A new endpoint is in the matrix as soon as it is in the document.
 */
class AuthorisationMatrixIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STAFF_JWT = "staffJwt";

    @TestFactory
    List<DynamicTest> everyOperationIsGuardedAsItsDocumentSays() {
        JsonNode paths = JSON.readTree(http().get().uri("/v3/api-docs").retrieve().body(String.class)).get("paths");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            // any id: the guard answers before the controller looks the resource up
            String uri = path.getKey().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                HttpMethod method = HttpMethod.valueOf(operation.getKey().toUpperCase());
                boolean staff = needsStaffJwt(operation.getValue());
                tests.add(DynamicTest.dynamicTest(method + " " + path.getKey(), () -> {
                    if (staff) {
                        var anonymous = call(method, uri, null);
                        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
                        assertThat(anonymous.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
                        var wrongRole = call(method, uri, StaffTokens.token("compliance-1", "COMPLIANCE_OFFICER"));
                        assertThat(wrongRole.getStatusCode().value()).isEqualTo(403);
                        assertThat(wrongRole.getBody()).contains("\"code\":\"FORBIDDEN\"");
                    } else {
                        assertThat(call(method, uri, null).getStatusCode().value())
                                .as("an @InternalApi operation takes no token until M5").isNotIn(401, 403);
                    }
                }));
            }
        }
        assertThat(tests).as("the document lists operations").isNotEmpty();
        return tests;
    }

    @Test
    void probesTheMetricsScrapeAndTheDocumentNeedNoToken() {
        for (String open : List.of("/actuator/health/liveness", "/actuator/prometheus", "/v3/api-docs")) {
            assertThat(call(HttpMethod.GET, open, null).getStatusCode().value()).as(open).isEqualTo(200);
        }
    }

    private static boolean needsStaffJwt(JsonNode operation) {
        JsonNode security = operation.get("security");
        if (security == null) {
            return false;
        }
        for (JsonNode requirement : security) {
            if (requirement.has(STAFF_JWT)) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<String> call(HttpMethod method, String uri, String token) {
        var request = http().method(method).uri(uri).headers(headers -> {
            if (token != null) {
                headers.setBearerAuth(token);
            }
        });
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            request = request.contentType(MediaType.APPLICATION_JSON).body("{}");
        }
        return request.retrieve().toEntity(String.class);
    }
}
