package com.tpe.paymentapi;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Bearer tokens as the issuer of specs/00-engineering-baseline.md §12.1 signs them ({@code sub}, {@code client_id},
 * {@code scope}, {@code accounts}), from a key made for this JVM; the service trusts its public key
 * ({@link #publicKeyLocation()}) instead of the issuer's.
 */
public final class Tokens {

    public static final String CLIENT = "mobile-app";
    public static final String SUBJECT = "customer-4711";

    private static final KeyPair KEYS = keys();
    private static final Path PUBLIC_KEY = publicKeyFile();

    private Tokens() {}

    /** {@code spring.security.oauth2.resourceserver.jwt.public-key-location}. */
    public static String publicKeyLocation() {
        return PUBLIC_KEY.toUri().toString();
    }

    /** The channel {@link #CLIENT}'s customer, who may debit {@code accounts}. */
    public static String customer(String scope, String... accounts) {
        return bearer(claims(CLIENT, scope, List.of(accounts)).build(), KEYS);
    }

    /** As the channel's customer, but without the {@code client_id} claim. */
    public static String withoutClientId(String scope, String account) {
        return bearer(claims(null, scope, List.of(account)).build(), KEYS);
    }

    /** As the channel's customer, but without the {@code accounts} claim. */
    public static String withoutAccounts(String scope) {
        return bearer(claims(CLIENT, scope, null).build(), KEYS);
    }

    /** As {@link #customer}, but signed by another key. */
    public static String foreign(String scope, String account) {
        return bearer(claims(CLIENT, scope, List.of(account)).build(), keys());
    }

    private static JWTClaimsSet.Builder claims(String clientId, String scope, List<String> accounts) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(SUBJECT)
                .claim("client_id", clientId)
                .claim("scope", scope)
                .claim("accounts", accounts)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
    }

    private static String bearer(JWTClaimsSet claims, KeyPair keys) {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(keys.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return "Bearer " + jwt.serialize();
    }

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has RSA", e);
        }
    }

    private static Path publicKeyFile() {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        try {
            Path file = Files.createTempFile("payment-api-test-issuer", ".pem");
            file.toFile().deleteOnExit();
            return Files.writeString(file, pem, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
