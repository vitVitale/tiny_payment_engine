package com.tpe.paymentorchestrator;

import com.tpe.testsupport.pact.StubConformanceTest;
import java.nio.file.Path;

/**
 * The ledger-service WireMock mappings of the component tests against P-04, the pact {@code LedgerServicePactTest}
 * wrote (specs/20-testing-strategy.md §10.4): a stubbed hold or settlement is one ledger-service actually gives.
 */
class LedgerStubConformanceIT extends StubConformanceTest {

    @Override
    protected Path pact() {
        return Path.of("target/pacts/payment-orchestrator-ledger-service.json");
    }

    @Override
    protected Path mappings() {
        return Path.of("src/integrationTest/resources/wiremock/ledger-service");
    }
}
