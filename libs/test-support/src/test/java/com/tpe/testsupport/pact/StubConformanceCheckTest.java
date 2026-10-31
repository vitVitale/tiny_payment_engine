package com.tpe.testsupport.pact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link StubConformance} (specs/20-testing-strategy.md §10.4) on a hand-written pact and WireMock mappings. */
class StubConformanceCheckTest {

    private static final Path FIXTURES = Path.of("src/test/resources/stub-conformance");
    private static final Path PACT = FIXTURES.resolve("pact.json");

    @Test
    void aBusinessMappingThatMatchesAnInteractionConforms() {
        assertThat(StubConformance.violations(PACT, FIXTURES.resolve("matching"))).isEmpty();
    }

    @Test
    void aBusinessMappingWithAResponseThePactDoesNotPromiseFails() {
        List<StubConformance.Violation> violations = StubConformance.violations(PACT, FIXTURES.resolve("other-code"));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.mapping().getFileName()).hasToString("insufficient-funds.json");
            assertThat(violation.reason()).contains("create hold: insufficient funds").contains("code");
        });
    }

    @Test
    void aBusinessMappingWithoutAMatchingInteractionFails() {
        List<StubConformance.Violation> violations = StubConformance.violations(PACT, FIXTURES.resolve("unknown-call"));

        assertThat(violations).singleElement().satisfies(violation ->
                assertThat(violation.reason()).contains("no interaction").contains("/settle"));
    }

    @Test
    void aConstraintTheCheckCannotCompareIsAViolationNotIgnored() {
        List<StubConformance.Violation> violations = StubConformance.violations(PACT, FIXTURES.resolve("unsupported"));

        assertThat(violations).singleElement().satisfies(violation ->
                assertThat(violation.reason()).contains("matchesJsonPath"));
    }

    @Test
    void aRequestHeaderTheMappingRequiresMustBeOneThePactSends() {
        assertThat(StubConformance.violations(PACT, FIXTURES.resolve("header-mismatch"))).singleElement()
                .satisfies(violation -> assertThat(violation.reason()).contains("no interaction"));
        assertThat(StubConformance.violations(PACT, FIXTURES.resolve("header-match"))).isEmpty();
    }

    @Test
    void aFaultMappingIsExempt() {
        assertThat(StubConformance.violations(PACT, FIXTURES.resolve("fault"))).isEmpty();
    }
}
