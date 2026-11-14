package com.tpe.paymentapi.api.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code request_hash} of the idempotency guard (specs/10-payment-api.md §5.1): hex SHA-256 of the method, a
 * space, the path, a newline and the JSON body as the service read it, re-serialised with object properties sorted at
 * every level and no whitespace. A retry that differs only in property order, whitespace or fields the API ignores is
 * the same request.
 */
final class RequestHash {

    private final JsonMapper json;

    RequestHash(JsonMapper json) {
        this.json = json;
    }

    String of(String method, String path, Object body) {
        String canonical = json.writeValueAsString(sorted(json.valueToTree(body)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((method + " " + path + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            node.propertyNames().stream().sorted().forEach(name -> sorted.set(name, sorted(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> array.add(sorted(element)));
            return array;
        }
        return node;
    }
}
