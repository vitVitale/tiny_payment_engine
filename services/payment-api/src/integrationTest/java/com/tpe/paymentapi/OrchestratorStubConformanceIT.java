package com.tpe.paymentapi;

import com.tpe.testsupport.pact.StubConformanceTest;
import java.nio.file.Path;

/**
 * The payment-orchestrator WireMock mappings of the component tests against P-01, the pact the consumer test wrote
 * (specs/20-testing-strategy.md §10.4): a stubbed answer is one payment-orchestrator actually gives.
 */
class OrchestratorStubConformanceIT extends StubConformanceTest {

    @Override
    protected Path pact() {
        return Path.of("target/pacts/payment-api-payment-orchestrator.json");
    }

    @Override
    protected Path mappings() {
        return Path.of("src/integrationTest/resources/wiremock/payment-orchestrator");
    }
}
