package com.tpe.ledgerservice.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Test funding never reaches production (specs/12-ledger-service.md §7, LED-AC-15): in profile {@code prod} the
 * service refuses to start with {@code tpe.ledger.funding.enabled=true}, so a stray override cannot create money.
 */
@Configuration(proxyBeanMethods = false)
class FundingGuard {

    static final String PROD = "prod";

    FundingGuard(Environment environment, @Value("${tpe.ledger.funding.enabled:false}") boolean fundingEnabled) {
        if (fundingEnabled && environment.acceptsProfiles(Profiles.of(PROD))) {
            throw new IllegalStateException(
                    "tpe.ledger.funding.enabled must be false in profile prod (specs/12-ledger-service.md §7)");
        }
    }
}
