package com.tpe.ledgerservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** {@code request_hash} of specs/12-ledger-service.md §5: SHA-256 of canonical JSON body + path. */
class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint(JsonMapper.builder().build());

    @Test
    void isTheHexSha256OfPathAndCanonicalBody() {
        assertThat(fingerprint.of("/v1/x", Map.of("b", 2, "a", 1))).isEqualTo(sha256("/v1/x\n{\"a\":1,\"b\":2}"));
    }

    @Test
    void ignoresTheOrderOfPropertiesAtEveryLevel() {
        var first = new LinkedHashMap<String, Object>();
        first.put("customerId", "C1");
        first.put("holder", ordered("type", "PERSON", "name", "Anna"));
        var second = new LinkedHashMap<String, Object>();
        second.put("holder", ordered("name", "Anna", "type", "PERSON"));
        second.put("customerId", "C1");

        assertThat(fingerprint.of("/v1/accounts", first)).isEqualTo(fingerprint.of("/v1/accounts", second));
    }

    @Test
    void differsByBodyByPathAndByArrayOrder() {
        String base = fingerprint.of("/v1/a", Map.of("x", List.of(1, 2)));

        assertThat(fingerprint.of("/v1/b", Map.of("x", List.of(1, 2)))).isNotEqualTo(base);
        assertThat(fingerprint.of("/v1/a", Map.of("x", List.of(2, 1)))).isNotEqualTo(base);
        assertThat(fingerprint.of("/v1/a", Map.of("x", List.of(1, 3)))).isNotEqualTo(base);
        assertThat(base).matches("[0-9a-f]{64}");
    }

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        var map = new LinkedHashMap<String, Object>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
