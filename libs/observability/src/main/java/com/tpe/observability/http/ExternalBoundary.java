package com.tpe.observability.http;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * No trace headers to external parties (specs/26-observability.md §5.3, ADR-014): removes {@code traceparent},
 * {@code tracestate} and {@code baggage} from requests of every Boot-built {@code RestClient} to a host in
 * {@code tpe.observability.external-hosts} (the CSM, counterparty VoP responders, webhook receivers, the sanctions
 * provider). The client span is still recorded; only the propagation is cut.
 */
public final class ExternalBoundary implements RestClientCustomizer, ClientHttpRequestInterceptor {

    static final List<String> TRACE_HEADERS = List.of("traceparent", "tracestate", "baggage");

    private final Set<String> hosts;

    public ExternalBoundary(Collection<String> hosts) {
        this.hosts = hosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void customize(RestClient.Builder builder) {
        builder.requestInterceptor(this);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String host = request.getURI().getHost();
        if (host != null && hosts.contains(host.toLowerCase(Locale.ROOT))) {
            TRACE_HEADERS.forEach(request.getHeaders()::remove);
        }
        return execution.execute(request, body);
    }
}
