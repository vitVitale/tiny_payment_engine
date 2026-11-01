package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The checked-in OpenAPI document matches the code (specs/00-engineering-baseline.md §7). The build step that
 * generates it: when the document at {@code /v3/api-docs} differs, this test writes it to {@link #CHECKED_IN} and
 * fails, so the change shows in the working tree to be committed and CI fails until it is.
 */
class OpenApiDocumentIT extends LedgerStack {

    static final Path CHECKED_IN = Path.of("src/main/resources/openapi/ledger-service.json");

    @Test
    void theCheckedInOpenApiDocumentMatchesTheCode() throws IOException {
        String served = http().get().uri("/v3/api-docs").retrieve().body(String.class);
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode document = (ObjectNode) json.readTree(served);
        // the random port of this test
        document.remove("servers");
        String generated = json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";

        String checkedIn = Files.exists(CHECKED_IN) ? Files.readString(CHECKED_IN, StandardCharsets.UTF_8) : "";
        if (!generated.equals(checkedIn)) {
            Files.createDirectories(CHECKED_IN.getParent());
            Files.writeString(CHECKED_IN, generated, StandardCharsets.UTF_8);
        }
        assertThat(checkedIn)
                .as("%s is out of date; it has been regenerated, commit it", CHECKED_IN)
                .isEqualTo(generated);
    }
}
