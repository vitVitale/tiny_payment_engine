package com.tpe.csmsimulator.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code tpe.sim.*} (specs/17-csm-simulator.md §4); the defaults are in {@code application.yml}.
 *
 * @param ownBic the CSM's BIC
 * @param answerTimeout how long the CSM waits for our answer to an incoming payment (not used before M4)
 * @param timeoutDeadline the scheme time-out deadline, counted from {@code AccptncDtTm}
 * @param tick how often the timeline runs what is due
 */
@ConfigurationProperties("tpe.sim")
public record SimulatorProperties(URI gatewayUrl, String ownBic, Duration beneficiaryDelay, Duration answerTimeout,
        Duration timeoutDeadline, Duration tick) {

    public SimulatorProperties {
        Objects.requireNonNull(gatewayUrl, "tpe.sim.gateway-url");
        Objects.requireNonNull(ownBic, "tpe.sim.own-bic");
        Objects.requireNonNull(beneficiaryDelay, "tpe.sim.beneficiary-delay");
        Objects.requireNonNull(answerTimeout, "tpe.sim.answer-timeout");
        Objects.requireNonNull(timeoutDeadline, "tpe.sim.timeout-deadline");
        Objects.requireNonNull(tick, "tpe.sim.tick");
    }
}
