package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The ordering rule of specs/20-testing-strategy.md §2.1 across the services' timings, over the default profile (what
 * production runs) and the {@code systemtest} profile (what the system tests run): the short timings keep the order of
 * the production ones, so the same races occur.
 *
 * <ul>
 *   <li>{@code send-by-offset < deadline-offset = sim.timeout-deadline}: a transfer is sent before the scheme deadline,
 *   which the CSM and the orchestrator count alike;</li>
 *   <li>{@code answer-deadline-offset + answer-transmit-window ≤ sim.answer-timeout}: the gateway's last answer
 *   reaches the CSM before it gives up waiting;</li>
 *   <li>{@code settlement-overdue > sim.beneficiary-delay}: an ordinary settlement is never taken for a missing
 *   one;</li>
 *   <li>the gateway's consumer {@code session.timeout.ms < answer-deadline-offset}, its {@code heartbeat-interval} a
 *   third of the session at most: a dead gateway's verdicts move on before the answer deadline (15 §4).</li>
 * </ul>
 */
class ProfileOrderingTest {

    @ParameterizedTest(name = "{0} profile")
    @ValueSource(strings = {"default", "systemtest"})
    void theTimingsOfTheServicesKeepTheSchemesOrder(String profile) {
        Function<ServiceJar, ServiceProfile> load = service -> profile.equals("default")
                ? ServiceProfile.of(service.resources())
                : ServiceProfile.systemtest(service);
        ServiceProfile orchestrator = load.apply(ServiceJar.ORCHESTRATOR);
        ServiceProfile gateway = load.apply(ServiceJar.CSM_GATEWAY);
        ServiceProfile simulator = load.apply(ServiceJar.CSM_SIMULATOR);

        Duration sendBy = required(orchestrator, "tpe.outgoing.send-by-offset");
        Duration deadline = required(orchestrator, "tpe.outgoing.deadline-offset");
        assertThat(sendBy).as("send-by-offset < deadline-offset").isLessThan(deadline);
        assertThat(deadline).as("deadline-offset = sim.timeout-deadline")
                .isEqualTo(required(simulator, "tpe.sim.timeout-deadline"));

        assertThat(required(gateway, "tpe.csm.answer-deadline-offset")
                .plus(required(gateway, "tpe.csm.answer-transmit-window")))
                .as("answer-deadline-offset + answer-transmit-window <= sim.answer-timeout")
                .isLessThanOrEqualTo(required(simulator, "tpe.sim.answer-timeout"));

        assertThat(required(orchestrator, "tpe.incoming.settlement-overdue"))
                .as("settlement-overdue > sim.beneficiary-delay")
                .isGreaterThan(required(simulator, "tpe.sim.beneficiary-delay"));

        Duration session = required(gateway, "spring.kafka.consumer.properties.session.timeout.ms");
        assertThat(session).as("gateway session.timeout.ms < answer-deadline-offset")
                .isLessThan(required(gateway, "tpe.csm.answer-deadline-offset"));
        assertThat(required(gateway, "spring.kafka.consumer.heartbeat-interval").multipliedBy(3))
                .as("gateway heartbeat-interval <= session.timeout.ms / 3").isLessThanOrEqualTo(session);
    }

    private static Duration required(ServiceProfile profile, String key) {
        return profile.duration(key).orElseThrow(() -> new AssertionError(key + " is not set"));
    }
}
