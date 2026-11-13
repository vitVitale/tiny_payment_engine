package com.tpe.paymentorchestrator.api.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code request_hash} of a submit (specs/11-payment-orchestrator.md §3.1, §5): hex SHA-256 of the path, a
 * newline and the canonical JSON of the request as the service read it (object properties sorted at every level, no
 * whitespace), as ledger-service does (specs/12-ledger-service.md §6.1). A retry whose body differs only in property
 * order or in fields the service ignores has the same hash.
 */
@Component
class RequestFingerprint {

    private final JsonMapper json;

    RequestFingerprint(JsonMapper json) {
        this.json = json;
    }

    String of(String path, Object request) {
        String canonical = json.writeValueAsString(sorted(json.valueToTree(request)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((path + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
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
