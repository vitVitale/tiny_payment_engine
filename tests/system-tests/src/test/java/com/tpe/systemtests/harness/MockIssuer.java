package com.tpe.systemtests.harness;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tokens from the compose stack's mock OAuth2 issuer (deploy/oauth2/config.json, specs/00-engineering-baseline.md
 * §12.1): staff tokens with a {@code roles} claim, service client-credentials tokens with scopes (ADR-012), and
 * customer-channel tokens with scopes and the {@code accounts} claim.
 */
public final class MockIssuer {

    /** The {@code client_id} of customer-channel tokens: the API client a customer uses. */
    public static final String CUSTOMER_CHANNEL = "tpe-customer-channel";

    private static final URI TOKEN = URI.create("http://localhost:8099/default/token");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockIssuer() {}

    /** A staff member's {@code Authorization} value: {@code sub} = {@code subject}, {@code roles} = [{@code role}]. */
    public static String staff(String subject, String role) {
        return bearer(TOKEN, Map.of("grant_type", "client_credentials", "client_id", "tpe-backoffice",
                "client_secret", "local", "sub", subject, "roles", role));
    }

    /** risk-service's {@code Authorization} value: client {@code risk-service}, scope {@code ledger:sanctions-block}. */
    public static String riskService() {
        return bearer(TOKEN, Map.of("grant_type", "client_credentials", "client_id", "risk-service",
                "client_secret", "local", "sub", "risk-service", "scope", "ledger:sanctions-block"));
    }

    /**
     * A customer's {@code Authorization} value from the channel {@link #CUSTOMER_CHANNEL}: {@code sub} =
     * {@code subject}, {@code scope} = the {@code scopes}, {@code accounts} = [{@code iban}]. One IBAN per token: the
     * mock issuer copies a request parameter into the claim as a single array element.
     */
    public static String customer(String subject, String iban, CustomerScope... scopes) {
        return customer(TOKEN, subject, iban, scopes);
    }

    static String customer(URI token, String subject, String iban, CustomerScope... scopes) {
        var form = new LinkedHashMap<String, String>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", CUSTOMER_CHANNEL);
        form.put("client_secret", "local");
        form.put("sub", subject);
        form.put("scope", Arrays.stream(scopes).map(CustomerScope::value).collect(Collectors.joining(" ")));
        form.put("accounts", iban);
        return bearer(token, form);
    }

    /** The claims of an {@code Authorization} value's JWT, not verified: what a service reads once it trusts it. */
    public static JsonNode claims(String authorization) {
        String[] parts = authorization.substring("Bearer ".length()).split("\\.");
        return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static String bearer(URI token, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(token)
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
            throw new IllegalStateException("mock issuer unreachable at " + token, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
