package com.tpe.csmsimulator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.paymentdomain.MutableClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The simulator on a random port with WireMock as csm-gateway's inbound endpoint ({@code 202} for everything), started
 * once per JVM; every subclass shares one Spring context. Every test starts from {@code POST /sim/v1/reset}, no
 * WireMock requests, and the simulator's time at {@link #START} ({@link #CLOCK}); time moves only when a test moves it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SimulatorStack.TestClock.class)
public abstract class SimulatorStack {

    protected static final Instant START = Instant.parse("2026-11-15T10:00:00.000Z");

    /** The simulator's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(START);

    /** csm-gateway's {@code POST /csm/v1/inbound}. */
    protected static final WireMockServer GATEWAY = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        GATEWAY.start();
    }

    @LocalServerPort
    protected int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void stack(DynamicPropertyRegistry registry) {
        registry.add("tpe.sim.gateway-url", GATEWAY::baseUrl);
    }

    @BeforeEach
    void cleanSlate() throws Exception {
        CLOCK.set(START);
        send("POST", "/sim/v1/reset", "application/json", "");
        GATEWAY.resetAll();
        GATEWAY.stubFor(post("/csm/v1/inbound").willReturn(aResponse().withStatus(202)));
    }

    /** What the simulator sent to csm-gateway, oldest first. */
    protected static List<String> sentToGateway() {
        return GATEWAY.findAll(postRequestedFor(urlEqualTo("/csm/v1/inbound"))).stream()
                .map(LoggedRequest::getBodyAsString).toList();
    }

    protected HttpResponse<String> send(String method, String path, String contentType, String body)
            throws IOException, InterruptedException {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    protected HttpResponse<String> get(String path) throws IOException, InterruptedException {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** {@code POST /csm/v1/messages}, as csm-gateway's transmitter sends an envelope. */
    protected HttpResponse<String> transmit(String envelope) throws IOException, InterruptedException {
        return send("POST", "/csm/v1/messages", "application/xml", envelope);
    }
}
