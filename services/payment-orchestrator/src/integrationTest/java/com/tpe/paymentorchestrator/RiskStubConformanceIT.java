package com.tpe.paymentorchestrator;

import com.tpe.testsupport.pact.StubConformanceTest;
import java.nio.file.Path;

/**
 * The risk-service WireMock mappings of the component tests against P-03, the pact {@code RiskServicePactTest}
 * wrote (specs/20-testing-strategy.md §10.4): a stubbed decision is one risk-service actually gives.
 */
class RiskStubConformanceIT extends StubConformanceTest {

    @Override
    protected Path pact() {
        return Path.of("target/pacts/payment-orchestrator-risk-service.json");
    }

    @Override
    protected Path mappings() {
        return Path.of("src/integrationTest/resources/wiremock/risk-service");
    }
}
