package com.tpe.csmgateway.infrastructure.csm;

import com.tpe.csmgateway.application.transmit.CsmClient;
import com.tpe.observability.http.ExternalBoundary;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@code POST {tpe.csm.base-url}/csm/v1/messages} with the envelope as UTF-8 XML (specs/15-csm-gateway.md §3.1, P-06),
 * timeouts {@code tpe.csm.timeouts.*} (§7), without trace headers. mTLS in {@code prod} is not configured yet.
 */
public final class HttpCsmClient implements CsmClient {

    static final MediaType XML = new MediaType("application", "xml", StandardCharsets.UTF_8);

    private final RestClient client;

    public HttpCsmClient(RestClient.Builder builder, URI baseUrl, Duration connectTimeout, Duration readTimeout) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requests.setReadTimeout(readTimeout);
        // the CSM is outside our trust boundary wherever it runs: no trace headers to it (ADR-014 §5)
        this.client = builder.clone().baseUrl(baseUrl.toString()).requestFactory(requests)
                .requestInterceptor(new ExternalBoundary(List.of(baseUrl.getHost()))).build();
    }

    @Override
    public void send(String envelope) {
        try {
            client.post().uri("/csm/v1/messages").contentType(XML).body(envelope.getBytes(StandardCharsets.UTF_8))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            // the status only: the body may quote the envelope, which holds names and IBANs (00 §11)
            throw new CsmUnavailableException("HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new CsmUnavailableException(e.getClass().getSimpleName(), e);
        }
    }
}
