package com.tpe.vopservice.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The active pepper of {@code responder_audit} (specs/13-vop-service.md §5, ADR-017): values are stored as
 * HMAC-SHA-256 keyed with the pepper, lower-case hex, next to the pepper's {@code version}, so an evidence lookup
 * hashes with the row's version. {@link #toString()} leaves the pepper out.
 */
public record AuditPepper(short version, String pepper) {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    public AuditPepper {
        Objects.requireNonNull(pepper, "pepper");
        if (version < 1) {
            throw new IllegalArgumentException("pepper versions start at 1");
        }
        if (pepper.isBlank()) {
            throw new IllegalArgumentException("pepper " + version + " is blank");
        }
    }

    /** 64 lower-case hex characters. */
    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is part of every JDK", e);
        }
    }

    @Override
    public String toString() {
        return "AuditPepper[version=" + version + "]";
    }
}
