package com.tpe.paymentapi;

import com.tpe.testsupport.pact.StubConformanceTest;
import java.nio.file.Path;

/**
 * The vop-service WireMock mappings of the component tests against P-02, the pact the consumer test wrote
 * (specs/20-testing-strategy.md §10.4): a stubbed answer is one vop-service actually gives.
 */
class VopStubConformanceIT extends StubConformanceTest {

    @Override
    protected Path pact() {
        return Path.of("target/pacts/payment-api-vop-service.json");
    }

    @Override
    protected Path mappings() {
        return Path.of("src/integrationTest/resources/wiremock/vop-service");
    }
}
