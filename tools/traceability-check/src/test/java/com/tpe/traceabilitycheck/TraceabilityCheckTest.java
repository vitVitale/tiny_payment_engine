package com.tpe.traceabilitycheck;

import static com.tpe.traceabilitycheck.Violation.Reason.ADDED_BUT_ON_MAIN;
import static com.tpe.traceabilitycheck.Violation.Reason.NO_CONSUMER_PACT_TEST;
import static com.tpe.traceabilitycheck.Violation.Reason.NO_PROVIDER_VERIFICATION;
import static com.tpe.traceabilitycheck.Violation.Reason.NO_TEST;
import static com.tpe.traceabilitycheck.Violation.Reason.PENDING_BUT_NOT_IN_SPECS;
import static com.tpe.traceabilitycheck.Violation.Reason.PENDING_BUT_TESTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.traceabilitycheck.TraceabilityCheck.Base;
import com.tpe.traceabilitycheck.TraceabilityCheck.Snapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TraceabilityCheckTest {

    private static final String TESTING_STRATEGY = "specs/20-testing-strategy.md";

    private static final Map<String, String> INVENTORY = Map.of(TESTING_STRATEGY, """
            ### 10.1 Integration inventory

            | Id | Consumer → provider | Interface | Interactions (at least) |
            |---|---|---|---|
            | P-04 | payment-orchestrator → ledger-service | holds | create hold `201` |
            | M-01 | payment-orchestrator → csm-gateway | Kafka `csm.outbound.commands` | `SendCreditTransfer` |

            ### 10.2 Writing consumer pacts
            """);

    private static final String CONSUMER_P04 = """
            @PactTestFor(providerName = "ledger-service")
            class LedgerServicePactTest {
                @Pact(consumer = "payment-orchestrator")
                V4Pact PACT_P04_pactForM01(PactDslWithProvider builder) { return null; }

                @Test void PACT_P04_createHoldInsufficientFunds() {}
            }""";

    private static final String PROVIDER_P04 = """
            @Provider("ledger-service")
            @Tag("P-04")
            class LedgerServicePactVerificationIT {
                @TestTemplate void verify(PactVerificationContext context) {}
            }""";

    @Test
    void idWithoutTestAndNotPendingFails() {
        var current = snapshot(Map.of("specs/12-ledger-service.md", "- **LED-AC-09** two holds race"), Map.of(), Set.of());

        assertThat(check(current)).containsExactly(new Violation("LED-AC-09", NO_TEST));
    }

    @Test
    void idsCarriedByTestNamesOrTagsPass() {
        var specs = Map.of(
                "specs/01-shared-libraries.md", "MSG-AC-03 concurrent relays; GW-AC-12 duplicate pacs.008",
                TESTING_STRATEGY, "| E2E-O-12 | Gateway down |\n| CH-05 | Kafka partition |\n| CH-06 | x |");
        var tests = Map.of(
                "libs/messaging/src/integrationTest/java/RelayIT.java", """
                        class RelayIT {
                            @Test void MSG_AC_03_twoRelaysPublishOnce() {}
                            @org.junit.jupiter.api.Test @Tag("GW-AC-12") void duplicateIsIgnored() {}
                        }""",
                "tests/system-tests/src/test/java/OutgoingTest.java", """
                        @Tags({@Tag("CH-06"), @Tag(value = "smoke")})
                        class CH_05_KafkaPartitionTest {
                            @ParameterizedTest @ValueSource(ints = 1)
                            void E2E_O_12_gatewayDownPastSendBy(int i) {}
                        }""");

        assertThat(check(snapshot(specs, tests, Set.of()))).isEmpty();
    }

    @Test
    void idsOutsideTestDeclarationsAreNotCarried() {
        var specs = Map.of(TESTING_STRATEGY, "E2E-O-03, E2E-I-01, E2E-I-02, E2E-I-03, E2E-I-04, CH-01");
        var tests = Map.of(
                "Some.java", """
                        // E2E_O_03_insufficientFunds is still to do
                        class Some {
                            static final String E2E_I_02_FIXTURE = "x";
                            /* E2E-I-01 */ @Test void other() { log("E2E_I_01_x"); E2E_I_03_helper(); }
                            private void E2E_I_04_helper() {}
                        }""",
                "CH_01_Fixture.java", "class CH_01_Fixture { void build() {} }");

        assertThat(check(snapshot(specs, tests, Set.of()))).extracting(Violation::id)
                .containsExactly("CH-01", "E2E-I-01", "E2E-I-02", "E2E-I-03", "E2E-I-04", "E2E-O-03");
    }

    @Test
    void shortFormsAndRangesInTheSpecsAreExpanded() {
        var specs = Map.of("specs/30-delivery-plan.md", """
                Exit: ISO-AC-01, -02; E2E-O-06, -I-11 and -S-01; ORC-AC-21 … -23; CH-01 … CH-03; LED-AC-09, -10, GW-AC-12.
                """);

        assertThat(check(snapshot(specs, Map.of(), Set.of()))).extracting(Violation::id).containsExactly(
                "CH-01", "CH-02", "CH-03", "E2E-I-11", "E2E-O-06", "E2E-S-01", "GW-AC-12", "ISO-AC-01", "ISO-AC-02",
                "LED-AC-09", "LED-AC-10", "ORC-AC-21", "ORC-AC-22", "ORC-AC-23");
    }

    @Test
    void integrationNeedsConsumerPactTestAndProviderVerification() {
        var tests = Map.of(
                "LedgerServicePactTest.java", CONSUMER_P04,
                "CsmGatewayPactVerificationIT.java", """
                        @Provider("csm-gateway")
                        @Tag("M-01")
                        class CsmGatewayPactVerificationIT {
                            @TestTemplate void verify(PactVerificationContext context) {}
                        }""",
                "SomeUnitTest.java", "class SomeUnitTest { @Test void PACT_M01_notAPactTest() {} }");

        assertThat(check(snapshot(INVENTORY, tests, Set.of()))).containsExactly(
                new Violation("M-01", NO_CONSUMER_PACT_TEST), new Violation("P-04", NO_PROVIDER_VERIFICATION));
    }

    @Test
    void integrationWithBothSidesPasses() {
        var tests = Map.of("LedgerServicePactTest.java", CONSUMER_P04, "LedgerServicePactVerificationIT.java", PROVIDER_P04);

        assertThat(check(snapshot(INVENTORY, tests, Set.of("M-01")))).isEmpty();
    }

    @Test
    void pactMethodsAreNotTests() {
        var tests = Map.of("LedgerServicePactTest.java", """
                @PactTestFor(providerName = "ledger-service")
                class LedgerServicePactTest {
                    @Pact(consumer = "payment-orchestrator")
                    V4Pact PACT_P04_createHold(PactDslWithProvider builder) { return null; }
                    @Test void createHold() {}
                }""", "LedgerServicePactVerificationIT.java", PROVIDER_P04);

        assertThat(check(snapshot(INVENTORY, tests, Set.of("M-01"))))
                .containsExactly(new Violation("P-04", NO_CONSUMER_PACT_TEST));
    }

    @Test
    void integrationIdsOnlyComeFromTheInventoryTable() {
        var specs = Map.of(
                "specs/15-csm-gateway.md", "| P-05 | some other table |\nThe P-04 pact covers it; see M-02.",
                TESTING_STRATEGY, "### 10.3 Verifying pacts\n\n| P-06 | not the inventory |\n");

        assertThat(check(snapshot(specs, Map.of(), Set.of()))).isEmpty();
    }

    @Test
    void pendingIdThatNowHasATestMustBeRemoved() {
        var specs = Map.of("specs/02-messaging-contracts.md", "MSG-AC-03 and MSG-AC-04");
        var tests = Map.of("RelayIT.java", "class RelayIT { @Test void MSG_AC_03_twoRelays() {} }");

        assertThat(check(snapshot(specs, tests, Set.of("MSG-AC-03", "MSG-AC-04"))))
                .containsExactly(new Violation("MSG-AC-03", PENDING_BUT_TESTED));
    }

    @Test
    void pendingIntegrationStaysPendingUntilBothSidesExist() {
        var consumerOnly = Map.of("LedgerServicePactTest.java", CONSUMER_P04);
        var bothSides = Map.of("LedgerServicePactTest.java", CONSUMER_P04, "LedgerServicePactVerificationIT.java", PROVIDER_P04);

        assertThat(check(snapshot(INVENTORY, consumerOnly, Set.of("P-04", "M-01")))).isEmpty();
        assertThat(check(snapshot(INVENTORY, bothSides, Set.of("P-04", "M-01"))))
                .containsExactly(new Violation("P-04", PENDING_BUT_TESTED));
    }

    @Test
    void pendingIdThatIsNoLongerInTheSpecsMustBeRemoved() {
        var specs = Map.of("specs/12-ledger-service.md", "LED-AC-01");

        assertThat(check(snapshot(specs, Map.of(), Set.of("LED-AC-01", "LED-AC-99"))))
                .containsExactly(new Violation("LED-AC-99", PENDING_BUT_NOT_IN_SPECS));
    }

    @Test
    void baselineMayNotGrowByIdsThatExistedOnMain() {
        var mainSpecs = Map.of("specs/12-ledger-service.md", "LED-AC-01 LED-AC-02");
        var main = new Base(mainSpecs, Optional.of(Set.of("LED-AC-01")));
        var current = snapshot(mainSpecs, Map.of(), Set.of("LED-AC-01", "LED-AC-02"));

        assertThat(TraceabilityCheck.check(current, main)).containsExactly(new Violation("LED-AC-02", ADDED_BUT_ON_MAIN));
    }

    @Test
    void idsNewToTheSpecsMayEnterTheBaseline() {
        var main = new Base(Map.of("specs/12-ledger-service.md", "LED-AC-01"), Optional.of(Set.of("LED-AC-01")));
        var current = snapshot(
                Map.of("specs/12-ledger-service.md", "LED-AC-01 LED-AC-19", TESTING_STRATEGY, "### 10.1 Inventory\n| P-11 | x |"),
                Map.of(),
                Set.of("LED-AC-01", "LED-AC-19", "P-11"));

        assertThat(TraceabilityCheck.check(current, main)).isEmpty();
    }

    @Test
    void mainWithoutABaselineAcceptsTheInitialBaseline() {
        var specs = Map.of("specs/12-ledger-service.md", "LED-AC-01");

        assertThat(check(snapshot(specs, Map.of(), Set.of("LED-AC-01")))).isEmpty();
    }

    /** Checks against a main that has the same specs and no baseline yet (the bootstrap). */
    private static List<Violation> check(Snapshot current) {
        return TraceabilityCheck.check(current, new Base(current.specs(), Optional.empty()));
    }

    private static Snapshot snapshot(Map<String, String> specs, Map<String, String> tests, Set<String> pending) {
        return new Snapshot(specs, tests, pending);
    }
}
