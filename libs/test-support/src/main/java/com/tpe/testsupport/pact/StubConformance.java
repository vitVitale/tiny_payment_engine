package com.tpe.testsupport.pact;

import au.com.dius.pact.core.matchers.Mismatch;
import au.com.dius.pact.core.matchers.RequestMatching;
import au.com.dius.pact.core.matchers.ResponseMatching;
import au.com.dius.pact.core.model.ContentType;
import au.com.dius.pact.core.model.DefaultPactReader;
import au.com.dius.pact.core.model.IRequest;
import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.OptionalBody;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.Request;
import au.com.dius.pact.core.model.Response;
import au.com.dius.pact.core.model.SynchronousRequestResponse;
import au.com.dius.pact.core.support.json.JsonParser;
import au.com.dius.pact.core.support.json.JsonValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks WireMock mappings against the consumer's own pact (specs/20-testing-strategy.md §10.4): every mapping that
 * returns a business response must match an HTTP interaction of the pact with Pact's matchers, its request against
 * the interaction's request and its response against the interaction's response. Mappings with
 * {@code "metadata": { "pact": "fault" }} (delays, {@code 5xx}, resets) are exempt.
 *
 * <p>The request is compared on what the mapping constrains: method, exact {@code url} (path and query) or
 * {@code urlPath} with {@code equalTo} query parameters, {@code equalTo} / {@code matches} headers and an
 * {@code equalToJson} body; what it leaves open is taken from the interaction. The response is compared in full:
 * status, headers and body. Any other constraint (URL patterns and templates, other matchers, body files) cannot be
 * compared and is a violation, never ignored.
 */
public final class StubConformance {

    /** A mapping that does not conform, and why. */
    public record Violation(Path mapping, String reason) {

        @Override
        public String toString() {
            return mapping + ": " + reason;
        }
    }

    private static final String FAULT = "fault";
    private static final ContentType JSON = new ContentType("application/json");
    private static final Set<String> UNCHECKABLE_REQUEST = Set.of("urlPattern", "urlPathPattern", "urlPathTemplate",
            "cookies", "basicAuthCredentials", "multipartPatterns", "formParameters");
    private static final Set<String> UNCHECKABLE_RESPONSE = Set.of("bodyFileName", "base64Body", "fault",
            "proxyBaseUrl", "transformers");

    private StubConformance() {}

    /** The violations of the {@code *.json} mappings under {@code mappings} against the pact file {@code pact}. */
    public static List<Violation> violations(Path pact, Path mappings) {
        Pact loaded = DefaultPactReader.INSTANCE.loadPact(pact.toFile());
        List<Violation> violations = new ArrayList<>();
        for (Path mapping : mappingFiles(mappings)) {
            JsonValue.Object json = read(mapping);
            if (!isFault(json)) {
                check(loaded, json).map(reason -> new Violation(mapping, reason)).ifPresent(violations::add);
            }
        }
        return violations;
    }

    /** Why the mapping does not conform; empty if it does. */
    private static Optional<String> check(Pact pact, JsonValue.Object mapping) {
        JsonValue.Object request = mapping.get("request").asObject();
        JsonValue.Object response = mapping.get("response").asObject();
        Optional<String> uncheckable = uncheckable(request, response);
        if (uncheckable.isPresent()) {
            return uncheckable;
        }
        Stub stub = Stub.of(request);
        Response stubbed = response(response);
        List<String> responseMismatches = new ArrayList<>();
        for (Interaction interaction : pact.getInteractions()) {
            if (!interaction.isSynchronousRequestResponse()) {
                continue;
            }
            SynchronousRequestResponse http = interaction.asSynchronousRequestResponse();
            Optional<Request> actual = stub.request(http.getRequest());
            if (actual.isEmpty() || !RequestMatching.requestMismatches(pact, http, actual.get()).matchedOk()) {
                continue;
            }
            List<Mismatch> mismatches = ResponseMatching.responseMismatches(pact, http, stubbed);
            if (mismatches.isEmpty()) {
                return Optional.empty();
            }
            responseMismatches.add("'" + interaction.getDescription() + "': "
                    + mismatches.stream().map(Object::toString).toList());
        }
        return Optional.of(responseMismatches.isEmpty()
                ? "no interaction of the pact matches the request " + stub.call()
                : "the response of " + stub.call() + " is not one the pact promises: "
                        + String.join("; ", responseMismatches));
    }

    /** A constraint of the mapping this check cannot compare with Pact's matchers. */
    private static Optional<String> uncheckable(JsonValue.Object request, JsonValue.Object response) {
        for (String key : UNCHECKABLE_REQUEST) {
            if (request.has(key)) {
                return Optional.of("request." + key + " cannot be checked against the pact; business mappings name "
                        + "the exact path (url / urlPath) and use equalTo, matches (headers) or equalToJson");
            }
        }
        for (String key : UNCHECKABLE_RESPONSE) {
            if (response.has(key)) {
                return Optional.of("response." + key + " cannot be checked against the pact; inline the body as "
                        + "jsonBody or body, or mark a fault mapping with \"metadata\": { \"pact\": \"fault\" }");
            }
        }
        Optional<String> query = onlyOperators(request, "queryParameters", Set.of("equalTo"));
        Optional<String> headers = query.isPresent() ? query
                : onlyOperators(request, "headers", Set.of("equalTo", "matches"));
        if (headers.isPresent()) {
            return headers;
        }
        if (request.has("bodyPatterns")) {
            for (JsonValue pattern : request.get("bodyPatterns").asArray().getValues()) {
                for (String operator : pattern.asObject().getEntries().keySet()) {
                    if (!operator.equals("equalToJson")) {
                        return Optional.of("request.bodyPatterns." + operator + " cannot be checked against the "
                                + "pact; use equalToJson");
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> onlyOperators(JsonValue.Object request, String key, Set<String> supported) {
        if (!request.has(key)) {
            return Optional.empty();
        }
        for (Map.Entry<String, JsonValue> entry : request.get(key).asObject().getEntries().entrySet()) {
            for (String operator : entry.getValue().asObject().getEntries().keySet()) {
                if (!supported.contains(operator)) {
                    return Optional.of("request." + key + "." + entry.getKey() + "." + operator
                            + " cannot be checked against the pact; use " + String.join(" or ", supported));
                }
            }
        }
        return Optional.empty();
    }

    /** What a mapping's request constrains; {@link #request} fills in the rest from an interaction. */
    private record Stub(Optional<String> method, String path, Optional<Map<String, List<String>>> query,
            Map<String, String> equalHeaders, Map<String, Pattern> matchingHeaders, Optional<String> jsonBody) {

        static Stub of(JsonValue.Object request) {
            Optional<String> method = string(request, "method").filter(value -> !value.equals("ANY"));
            String url = string(request, "url").orElse(null);
            String path;
            Optional<Map<String, List<String>>> query;
            if (url != null) {
                // `url` is the exact URL: no query in it means no query
                int start = url.indexOf('?');
                path = start < 0 ? url : url.substring(0, start);
                query = Optional.of(start < 0 ? Map.of() : parseQuery(url.substring(start + 1)));
            } else {
                path = string(request, "urlPath").orElse("/");
                query = request.has("queryParameters") ? Optional.of(equalToValues(request, "queryParameters"))
                        : Optional.empty();
            }
            Map<String, String> equalHeaders = new LinkedHashMap<>();
            Map<String, Pattern> matchingHeaders = new LinkedHashMap<>();
            if (request.has("headers")) {
                request.get("headers").asObject().getEntries().forEach((name, rule) -> {
                    string(rule.asObject(), "equalTo").ifPresent(value -> equalHeaders.put(name, value));
                    string(rule.asObject(), "matches").ifPresent(regex -> matchingHeaders.put(name,
                            Pattern.compile(regex)));
                });
            }
            Optional<String> body = Optional.empty();
            if (request.has("bodyPatterns")) {
                for (JsonValue pattern : request.get("bodyPatterns").asArray().getValues()) {
                    JsonValue value = pattern.get("equalToJson");
                    body = Optional.of(value.isString() ? value.asString() : value.serialise());
                }
            }
            return new Stub(method, path, query, equalHeaders, matchingHeaders, body);
        }

        /**
         * The request this mapping stands for, against {@code expected}; empty if a {@code matches} header rejects
         * the header the interaction sends.
         */
        Optional<Request> request(IRequest expected) {
            Map<String, List<String>> headers = new LinkedHashMap<>(expected.getHeaders());
            for (Map.Entry<String, Pattern> rule : matchingHeaders.entrySet()) {
                Optional<String> sent = header(headers, rule.getKey());
                if (sent.isEmpty() || !rule.getValue().matcher(sent.get()).matches()) {
                    return Optional.empty();
                }
            }
            equalHeaders.forEach((name, value) -> {
                headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
                headers.put(name, List.of(value));
            });
            return Optional.of(new Request(method.orElse(expected.getMethod()), path,
                    query.orElse(expected.getQuery()), headers,
                    jsonBody.map(body -> OptionalBody.body(body, JSON)).orElse(expected.getBody())));
        }

        String call() {
            return method.orElse("ANY") + " " + path;
        }

        private static Optional<String> header(Map<String, List<String>> headers, String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst();
        }
    }

    private static Response response(JsonValue.Object stub) {
        int status = stub.has("status") ? stub.get("status").asNumber().intValue() : 200;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (stub.has("headers")) {
            stub.get("headers").asObject().getEntries().forEach((name, value) ->
                    headers.put(name, value.isArray()
                            ? value.asArray().getValues().stream().map(JsonValue::asString).toList()
                            : List.of(value.asString())));
        }
        ContentType contentType = headers.entrySet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase("Content-Type"))
                .map(header -> new ContentType(header.getValue().getFirst())).findFirst().orElse(null);
        OptionalBody body = OptionalBody.missing();
        if (stub.has("jsonBody")) {
            body = OptionalBody.body(stub.get("jsonBody").serialise(), contentType == null ? JSON : contentType);
        } else if (stub.has("body")) {
            body = OptionalBody.body(stub.get("body").asString(),
                    contentType == null ? new ContentType("text/plain") : contentType);
        }
        return new Response(status, headers, body);
    }

    private static Map<String, List<String>> parseQuery(String query) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] keyValue = pair.split("=", 2);
            parameters.computeIfAbsent(decode(keyValue[0]), key -> new ArrayList<>())
                    .add(keyValue.length > 1 ? decode(keyValue[1]) : "");
        }
        return parameters;
    }

    private static Map<String, List<String>> equalToValues(JsonValue.Object request, String key) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        request.get(key).asObject().getEntries().forEach((name, rule) ->
                string(rule.asObject(), "equalTo").ifPresent(value -> values.put(name, List.of(value))));
        return values;
    }

    private static Optional<String> string(JsonValue.Object json, String key) {
        return json.has(key) ? Optional.of(json.get(key).asString()) : Optional.empty();
    }

    private static boolean isFault(JsonValue.Object mapping) {
        return mapping.has("metadata") && string(mapping.get("metadata").asObject(), "pact").filter(FAULT::equals)
                .isPresent();
    }

    private static List<Path> mappingFiles(Path mappings) {
        try (Stream<Path> files = Files.walk(mappings)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonValue.Object read(Path mapping) {
        try {
            return JsonParser.parseString(Files.readString(mapping)).asObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
