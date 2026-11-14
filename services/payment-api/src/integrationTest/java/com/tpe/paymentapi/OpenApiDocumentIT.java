package com.tpe.paymentapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The checked-in {@code payment-api.yaml}, the contract for channel teams (specs/10-payment-api.md §3), matches the
 * code (specs/00-engineering-baseline.md §7). The build step that generates it: when the document at
 * {@code /v3/api-docs.yaml} differs, this test writes it to {@link #CHECKED_IN} and fails, so the change shows in the
 * working tree to be committed and CI fails until it is.
 */
class OpenApiDocumentIT extends PaymentApiStack {

    static final Path CHECKED_IN = Path.of("src/main/resources/openapi/payment-api.yaml");

    @Test
    void theCheckedInOpenApiDocumentMatchesTheCode() throws IOException {
        // UTF-8, as the media type names no charset; LF on every OS: a Windows checkout may turn the file's LF into
        // CRLF (core.autocrlf), which is no change
        String generated = new String(http().get().uri("/v3/api-docs.yaml").retrieve().body(byte[].class),
                StandardCharsets.UTF_8).replace("\r\n", "\n").stripTrailing() + "\n";
        String checkedIn = Files.exists(CHECKED_IN)
                ? Files.readString(CHECKED_IN, StandardCharsets.UTF_8).replace("\r\n", "\n")
                : "";
        if (!generated.equals(checkedIn)) {
            Files.createDirectories(CHECKED_IN.getParent());
            Files.writeString(CHECKED_IN, generated, StandardCharsets.UTF_8);
        }
        assertThat(checkedIn)
                .as("%s is out of date; it has been regenerated, commit it", CHECKED_IN)
                .isEqualTo(generated);
    }
}
