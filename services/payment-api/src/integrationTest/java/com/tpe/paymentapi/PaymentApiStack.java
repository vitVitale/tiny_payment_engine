package com.tpe.paymentapi;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The service on a random port with WireMock as payment-orchestrator and vop-service, started once per JVM; every
 * subclass shares one Spring context. Tokens are {@link Tokens}'; the bank directory is the default,
 * deploy/config/bank-directory.yml. The orchestrator answers what {@link #orchestratorAnswers} loaded last, vop-service
 * what {@link #vopAnswers} did. {@link #http()} never throws on an error status and never repeats a request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PaymentApiStack {

    /** payment-orchestrator (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer ORCHESTRATOR = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    /** vop-service (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer VOP = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** The example reference of P-02 a vop-service mapping names; {@link #vopAnswers} widens it to any reference. */
    private static final String EXAMPLE_CHECK = "\"url\": \"/v1/verifications/VOP-01JABCDEFGHJKMNPQRSTVW000";

    static {
        ORCHESTRATOR.start();
        VOP.start();
    }

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void stack(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location", Tokens::publicKeyLocation);
        registry.add("tpe.clients.orchestrator.base-url", ORCHESTRATOR::baseUrl);
        registry.add("tpe.clients.vop.base-url", VOP::baseUrl);
    }

    /** The orchestrator answers as {@code wiremock/payment-orchestrator/<name>.json} says. */
    protected static void orchestratorAnswers(String name) {
        ORCHESTRATOR.resetAll();
        ORCHESTRATOR.addStubMapping(StubMapping.buildFrom(mapping("payment-orchestrator", name)));
    }

    /**
     * vop-service answers as {@code wiremock/vop-service/<name>.json} says, for any reference; the mappings are
     * checked against P-02 by {@code VopStubConformanceIT} and name the pact's example references.
     */
    protected static void vopAnswers(String name) {
        VOP.resetAll();
        String mapping = mapping("vop-service", name);
        if (mapping.contains("/v1/verifications/VOP-")) {
            int start = mapping.indexOf(EXAMPLE_CHECK);
            if (start < 0) {
                throw new IllegalStateException(name + " names the example reference other than as \"url\": \"...\"");
            }
            int end = mapping.indexOf("/check\"", start);
            mapping = mapping.substring(0, start) + "\"urlPathPattern\": \"/v1/verifications/[^/]+/check\""
                    + mapping.substring(end + "/check\"".length());
        }
        VOP.addStubMapping(StubMapping.buildFrom(mapping));
    }

    private static String mapping(String provider, String name) {
        String path = "/wiremock/" + provider + "/" + name + ".json";
        try (var mapping = PaymentApiStack.class.getResourceAsStream(path)) {
            if (mapping == null) {
                throw new IllegalArgumentException("no mapping " + path);
            }
            return new String(mapping.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The JDK's client, which repeats nothing: the default on this classpath, Apache HttpClient, repeats a request
     * answered {@code 503} with {@code Retry-After} by itself.
     */
    protected RestClient http() {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }
}
