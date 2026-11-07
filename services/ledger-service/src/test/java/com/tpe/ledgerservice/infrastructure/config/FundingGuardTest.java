package com.tpe.ledgerservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** specs/12-ledger-service.md §7, LED-AC-15: profile prod does not start with test funding enabled. */
class FundingGuardTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(FundingGuard.class);

    @Test
    void prodWithFundingEnabledFailsToStart() {
        context.withInitializer(app -> app.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("tpe.ledger.funding.enabled=true")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("tpe.ledger.funding.enabled"));
    }

    @Test
    void prodWithFundingDisabledStarts() {
        context.withInitializer(app -> app.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("tpe.ledger.funding.enabled=false")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void otherProfilesMayEnableFunding() {
        context.withInitializer(app -> app.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("tpe.ledger.funding.enabled=true")
                .run(started -> assertThat(started).hasNotFailed());
    }
}
