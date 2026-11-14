package com.tpe.systemtests.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON over HTTP to one service as the harness calls it: every {@code POST} with a fresh {@code Idempotency-Key}
 * ({@code systest-<uuid>}), {@code Authorization} left out when {@code null}; never throws on an error status.
 */
public final class JsonHttp {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI baseUrl;

    public JsonHttp(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** {@code POST path} with {@code body} as JSON. */
    public Response post(String authorization, String path, Map<String, ?> body) {
        var request = HttpRequest.newBuilder(baseUrl.resolve(path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "systest-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        authorize(request, authorization);
        return send(request.build());
    }

    /** {@code GET path}. */
    public Response get(String authorization, String path) {
        var request = HttpRequest.newBuilder(baseUrl.resolve(path)).GET();
        authorize(request, authorization);
        return send(request.build());
    }

    /** A response read as text, e.g. a {@code pain.002}. */
    public record Text(int status, String contentType, String body) {}

    /** {@code GET path} accepting {@code accept}, the body as text. */
    public Text getText(String authorization, String path, String accept) {
        var request = HttpRequest.newBuilder(baseUrl.resolve(path)).header("Accept", accept).GET();
        authorize(request, authorization);
        HttpRequest built = request.build();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(built, HttpResponse.BodyHandlers.ofString());
            return new Text(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
                    response.body());
        } catch (IOException e) {
            throw new IllegalStateException("GET " + built.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void authorize(HttpRequest.Builder request, String authorization) {
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
    }

    private static Response send(HttpRequest request) {
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return new Response(response.statusCode(), body == null || body.isBlank() ? null : JSON.readTree(body));
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
