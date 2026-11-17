package com.tpe.csmsimulator.infrastructure.gateway;

import com.tpe.csmsimulator.application.GatewayClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@code POST {tpe.sim.gateway-url}/csm/v1/inbound} with the envelope as UTF-8 XML (specs/17-csm-simulator.md §3.1).
 * Timeouts as csm-gateway's own transport: connect 200 ms, read 1 s (specs/15-csm-gateway.md §6.5).
 */
public final class HttpGatewayClient implements GatewayClient {

    static final MediaType XML = new MediaType("application", "xml", StandardCharsets.UTF_8);

    private final RestClient client;

    public HttpGatewayClient(RestClient.Builder builder, URI gatewayUrl) {
        var requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build());
        requests.setReadTimeout(Duration.ofSeconds(1));
        this.client = builder.clone().baseUrl(gatewayUrl.toString()).requestFactory(requests).build();
    }

    @Override
    public int post(String message) {
        return client.post().uri("/csm/v1/inbound").contentType(XML).body(message.getBytes(StandardCharsets.UTF_8))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
