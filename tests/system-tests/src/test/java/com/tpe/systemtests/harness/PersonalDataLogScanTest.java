package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** INV-14 (specs/20-testing-strategy.md §3): the log scan and its negative test. */
class PersonalDataLogScanTest {

    private static final String IBAN = "DE36100100100000000001";
    private static final String NAME = "Test Abcdefgh Ijklmnopqr";

    @TempDir
    Path logs;

    @Test
    void logsWithoutTheTestsPersonalDataPass() throws IOException {
        write("ledger-service-systemtest.log", """
                {"log":{"level":"INFO"},"message":"account 0192f0e4 opened"}
                {"log":{"level":"INFO"},"message":"hold created for payment 0192f0e5"}
                """);

        assertThat(PersonalDataLogScan.violations(logs, List.of(IBAN, NAME))).isEmpty();
    }

    @Test
    void INV_14_anIbanOrANameAtInfoOrAboveFails() throws IOException {
        write("payment-api-systemtest.log", """
                {"log":{"level":"INFO"},"message":"payment from %s"}
                {"log":{"level":"ERROR"},"message":"cannot pay %s"}
                """.formatted(IBAN, NAME));

        assertThat(PersonalDataLogScan.violations(logs, List.of(IBAN, NAME)))
                .extracting(violation -> violation.invariant() + " " + violation.subject())
                .containsExactly("INV-14 payment-api-systemtest.log:1", "INV-14 payment-api-systemtest.log:2");
    }

    @Test
    void theViolationDoesNotRepeatThePersonalData() throws IOException {
        write("vop-service-systemtest.log", "{\"log\":{\"level\":\"WARN\"},\"message\":\"payee " + NAME + "\"}\n");

        assertThat(PersonalDataLogScan.violations(logs, List.of(NAME)))
                .singleElement().satisfies(violation -> assertThat(violation.detail()).doesNotContain(NAME));
    }

    @Test
    void debugAndTraceLinesAreNotScanned() throws IOException {
        write("vop-service-systemtest.log", """
                {"log":{"level":"DEBUG"},"message":"payee %s"}
                {"log":{"level":"TRACE"},"message":"payee %s"}
                """.formatted(NAME, NAME));

        assertThat(PersonalDataLogScan.violations(logs, List.of(NAME))).isEmpty();
    }

    /** A startup line before structured logging, a JVM crash: no level is no excuse. */
    @Test
    void aLineWithoutALevelIsScanned() throws IOException {
        write("risk-service-systemtest.log", "Exception in thread main: " + IBAN + "\n");

        assertThat(PersonalDataLogScan.violations(logs, List.of(IBAN))).hasSize(1);
    }

    @Test
    void onlyTheServiceLogsAreScanned() throws IOException {
        write("notes.txt", IBAN + "\n");

        assertThat(PersonalDataLogScan.violations(logs, List.of(IBAN))).isEmpty();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(logs.resolve(name), content);
    }
}
