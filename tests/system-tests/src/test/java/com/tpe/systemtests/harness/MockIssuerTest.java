package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Customer-channel tokens (issue #63, specs/00-engineering-baseline.md §12.1). A stub token endpoint answers as
 * deploy/oauth2/config.json maps an {@code accounts} request (claims {@code sub}, {@code client_id}, {@code scope},
 * {@code accounts} = [the parameter]); {@code MockIssuerIT} asks the real issuer of the compose stack.
 */
class MockIssuerTest {

    private static final String IBAN = "DE89370400440532013000";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer issuer;
    private URI token;

    @BeforeEach
    void startIssuer() throws IOException {
        issuer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuer.createContext("/default/token", exchange -> {
            Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var claims = new LinkedHashMap<String, Object>();
            claims.put("sub", form.get("sub"));
            claims.put("client_id", form.get("client_id"));
            claims.put("scope", form.get("scope"));
            claims.put("accounts", List.of(form.get("accounts")));
            byte[] body = JSON.writeValueAsBytes(Map.of("access_token", jwt(claims)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        issuer.start();
        token = URI.create("http://localhost:" + issuer.getAddress().getPort() + "/default/token");
    }

    @AfterEach
    void stopIssuer() {
        issuer.stop(0);
    }

    @Test
    void aCustomerTokenCarriesTheRequestedScopesAndTheAccountsClaim() {
        String authorization = MockIssuer.customer(token, "customer-anna", IBAN,
                CustomerScope.PAYMENTS_WRITE, CustomerScope.VOP_VERIFY);

        assertThat(authorization).startsWith("Bearer ");
        var claims = MockIssuer.claims(authorization);
        assertThat(claims.get("sub").asString()).isEqualTo("customer-anna");
        assertThat(claims.get("client_id").asString()).isEqualTo(MockIssuer.CUSTOMER_CHANNEL);
        assertThat(claims.get("scope").asString()).isEqualTo("payments:write vop:verify");
        assertThat(claims.get("accounts").values()).extracting(node -> node.asString()).containsExactly(IBAN);
    }

    @Test
    void theScopesAreTheOnesOfPaymentApi() {
        assertThat(CustomerScope.values()).extracting(CustomerScope::value)
                .containsExactly("payments:write", "payments:read", "vop:verify");
    }

    private static Map<String, String> form(String body) {
        var form = new LinkedHashMap<String, String>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            form.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return form;
    }

    /** An unsigned JWT: the harness reads claims, it does not verify signatures. */
    private static String jwt(Map<String, Object> claims) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(JSON.writeValueAsBytes(claims)) + ".";
    }
}
