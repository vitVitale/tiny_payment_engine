package com.tpe.testsupport.pact;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The shared fixture of specs/20-testing-strategy.md §10.4: every WireMock mapping of a consumer that returns a
 * business response matches an interaction of the consumer's own pact ({@link StubConformance}). A consumer module
 * extends it once per provider in {@code src/integrationTest} (an {@code *IT}), so the pact its unit tests wrote in
 * {@code target/pacts} exists when it runs (Surefire, which writes it, has finished by then):
 *
 * <pre>{@code
 * class LedgerStubConformanceIT extends StubConformanceTest {
 *     protected Path pact() { return Path.of("target/pacts/payment-orchestrator-ledger-service.json"); }
 *     protected Path mappings() { return Path.of("src/integrationTest/resources/wiremock/ledger-service"); }
 * }
 * }</pre>
 */
public abstract class StubConformanceTest {

    /** The consumer's pact with the provider the mappings stand in for. */
    protected abstract Path pact();

    /** The directory of the WireMock mappings ({@code *.json}, searched recursively). */
    protected abstract Path mappings();

    @Test
    void everyBusinessMappingMatchesAnInteractionOfThePact() {
        if (!Files.isRegularFile(pact())) {
            fail("No pact " + pact() + ": the consumer's pact test writes it (20 §10.2)");
        }
        assertTrue(Files.isDirectory(mappings()), "No mappings directory " + mappings());
        List<StubConformance.Violation> violations = StubConformance.violations(pact(), mappings());
        if (!violations.isEmpty()) {
            fail(violations.size() + " WireMock mapping(s) return a business response the pact does not promise; mark "
                    + "fault mappings with \"metadata\": { \"pact\": \"fault\" }:\n"
                    + violations.stream().map(Object::toString).collect(Collectors.joining("\n")));
        }
    }
}
