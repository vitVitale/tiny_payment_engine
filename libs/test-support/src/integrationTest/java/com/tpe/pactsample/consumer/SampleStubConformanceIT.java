package com.tpe.pactsample.consumer;

import com.tpe.testsupport.pact.StubConformanceTest;
import java.nio.file.Path;

/**
 * The sample consumer's WireMock mappings against the pact its {@code SampleProviderPactTest} wrote
 * (specs/20-testing-strategy.md §10.4).
 */
class SampleStubConformanceIT extends StubConformanceTest {

    @Override
    protected Path pact() {
        return Path.of("target/pacts/pact-sample-consumer-pact-sample-provider.json");
    }

    @Override
    protected Path mappings() {
        return Path.of("src/integrationTest/resources/wiremock/pact-sample-provider");
    }
}
