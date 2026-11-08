package com.tpe.systemtests.harness;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tokens from the compose stack's mock OAuth2 issuer (deploy/oauth2/config.json, specs/00-engineering-baseline.md
 * §12.1): staff tokens with a {@code roles} claim, and service client-credentials tokens with scopes (ADR-012).
 */
public final class MockIssuer {

    private static final URI TOKEN = URI.create("http://localhost:8099/default/token");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockIssuer() {}

    /** A staff member's {@code Authorization} value: {@code sub} = {@code subject}, {@code roles} = [{@code role}]. */
    public static String staff(String subject, String role) {
        return bearer(Map.of("grant_type", "client_credentials", "client_id", "tpe-backoffice",
                "client_secret", "local", "sub", subject, "roles", role));
    }

    /** risk-service's {@code Authorization} value: client {@code risk-service}, scope {@code ledger:sanctions-block}. */
    public static String riskService() {
        return bearer(Map.of("grant_type", "client_credentials", "client_id", "risk-service",
                "client_secret", "local", "sub", "risk-service", "scope", "ledger:sanctions-block"));
    }

    private static String bearer(Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(TOKEN)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("token request failed: " + response.statusCode() + " " + response.body());
            }
            return "Bearer " + JSON.readTree(response.body()).get("access_token").asString();
        } catch (IOException e) {
            throw new IllegalStateException("mock issuer unreachable at " + TOKEN, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
