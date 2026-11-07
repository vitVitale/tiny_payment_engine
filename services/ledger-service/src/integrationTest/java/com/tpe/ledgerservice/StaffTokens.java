package com.tpe.ledgerservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Staff JWTs as the issuer of specs/00-engineering-baseline.md §12.1 mints them ({@code sub}, {@code roles}), signed
 * with a key of this JVM; {@link Config} makes the service trust that key instead of the issuer's JWKS.
 */
public final class StaffTokens {

    private static final KeyPair KEYS = rsaKeys();

    private StaffTokens() {}

    /** A bearer token of {@code subject} with {@code roles}, valid for 5 minutes. */
    public static String token(String subject, String... roles) {
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("http://localhost:8099/default")
                .claim("roles", List.of(roles))
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    /**
     * A service's client-credentials token as ADR-012 describes it: {@code sub} = {@code client_id} = {@code clientId},
     * the {@code scopes} space-separated in {@code scope}, no roles. Valid for 5 minutes.
     */
    public static String serviceToken(String clientId, String... scopes) {
        var claims = new JWTClaimsSet.Builder()
                .subject(clientId)
                .issuer("http://localhost:8099/default")
                .claim("client_id", clientId)
                .claim("scope", String.join(" ", scopes))
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static KeyPair rsaKeys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }
}
