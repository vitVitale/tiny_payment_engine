package com.tpe.testsupport.pact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a {@link PactVerification} verifies (specs/20-testing-strategy.md §10.3). */
class PactVerificationLoaderTest {

    private static final Path PACT = Path.of("src/test/resources/stub-conformance/pact.json");

    @TempDir
    Path released;

    @Test
    void theLatestVersionByNumberNotByName() throws IOException {
        Files.createDirectories(released.resolve("0.9.0"));
        Files.createDirectories(released.resolve("0.10.0"));
        Files.createDirectories(released.resolve("0.2.1"));

        assertThat(PactVerificationLoader.latest(released)).contains(released.resolve("0.10.0"));
    }

    @Test
    void noReleaseYetIsNothingToVerify() throws IOException {
        Files.writeString(released.resolve("README.md"), "no release yet");

        assertThat(PactVerificationLoader.latest(released)).isEmpty();
        assertThat(PactVerificationLoader.latest(released.resolve("missing"))).isEmpty();
    }

    @TempDir
    Path collected;

    @Test
    void loadsThisCommitsAndTheLastReleasesPactsOfTheProviderOnly() throws IOException {
        Files.copy(PACT, collected.resolve("pact.json"));
        Path release = Files.createDirectories(released.resolve("0.1.0"));
        Files.copy(PACT, release.resolve("pact.json"));
        PactVerificationLoader loader = new PactVerificationLoader(collected,
                PactVerificationLoader.Released.in(released));

        assertThat(loader.load("ledger-service")).hasSize(2);
        assertThat(loader.load("risk-service")).isEmpty();
    }

    @Test
    void withoutAReleaseOnlyThisCommitsPacts() throws IOException {
        Files.copy(PACT, collected.resolve("pact.json"));

        assertThat(new PactVerificationLoader(collected, PactVerificationLoader.Released.in(released))
                .load("ledger-service")).hasSize(1);
    }

    @Test
    void theForwardCheckSkipsTheReleasedPacts() throws IOException {
        Files.copy(PACT, collected.resolve("pact.json"));
        Files.copy(PACT, Files.createDirectories(released.resolve("0.1.0")).resolve("pact.json"));

        assertThat(new PactVerificationLoader(collected, PactVerificationLoader.Released.skipped())
                .load("ledger-service")).hasSize(1);
    }

    @Test
    void foldersThatAreNotVersionsAreIgnored() throws IOException {
        Files.createDirectories(released.resolve("1.0.0"));
        Files.createDirectories(released.resolve("drafts"));

        assertThat(PactVerificationLoader.latest(released)).contains(released.resolve("1.0.0"));
    }
}
