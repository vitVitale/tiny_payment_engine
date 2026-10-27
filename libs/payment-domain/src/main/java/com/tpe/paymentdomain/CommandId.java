package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Identifier of a payment command, derived from the API client and its Idempotency-Key. */
public record CommandId(UUID value) {

    /** Namespace of {@link #derive}; fixed forever, a new value would break idempotency of stored commands. */
    public static final UUID TPE_COMMAND_NS = UUID.fromString("481802bd-0ea1-46e6-81c2-b39dc5132613");

    public CommandId {
        Objects.requireNonNull(value, "value");
    }

    /** UUID v5 in {@link #TPE_COMMAND_NS} of {@code "<clientId>\n<Idempotency-Key>"} (specs/00-engineering-baseline.md §4). */
    public static CommandId derive(String clientId, String idempotencyKey) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (clientId.indexOf('\n') >= 0) {
            throw new DomainValidationException(INVALID_CHARACTERS, "client id contains a line break");
        }
        return new CommandId(uuidV5(TPE_COMMAND_NS, clientId + "\n" + idempotencyKey));
    }

    private static UUID uuidV5(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK provides SHA-1", e);
        }
        sha1.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        ByteBuffer hash = ByteBuffer.wrap(sha1.digest(name.getBytes(StandardCharsets.UTF_8)));
        long msb = (hash.getLong() & ~0xF000L) | 0x5000L; // version 5
        long lsb = (hash.getLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // RFC 4122 variant
        return new UUID(msb, lsb);
    }
}
