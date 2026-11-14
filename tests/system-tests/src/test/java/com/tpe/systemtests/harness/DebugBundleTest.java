package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugBundleTest {

    @TempDir
    Path directory;

    @Test
    void theBundleIsNamedAfterTheTestAndStartsWithItsFailure() throws IOException {
        Path file = DebugBundle.write(directory, "OnUsPaymentIT.E2E_O_02", new AssertionError("INV-3"),
                "payment_transitions\n  RECEIVED -> SETTLED");

        assertThat(file).hasFileName("OnUsPaymentIT.E2E_O_02.txt");
        assertThat(Files.readString(file)).startsWith("failure: java.lang.AssertionError: INV-3")
                .contains("RECEIVED -> SETTLED");
    }
}
