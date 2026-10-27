package com.tpe.iso20022model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** The golden sample documents in {@code src/test/resources/golden}. */
final class GoldenSamples {

    private GoldenSamples() {}

    static String golden(String name) {
        try (InputStream in = GoldenSamples.class.getResourceAsStream("/golden/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
