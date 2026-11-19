package com.tpe.systemtests.harness;

/**
 * The chaos run of the failure-injection scenarios (specs/20-testing-strategy.md §5, CH-02 and CH-03 from M3, CH-04
 * from M4), in a test JVM of its own ({@code -Pchaos}, {@code -Dtpe.chaos=true}): the services in their default
 * timings, two payment-orchestrator and two csm-gateway instances, the orchestrators producing to Kafka through
 * Toxiproxy and the gateways consuming through a second proxy ({@link KafkaProxy}). Until M5 brings the chaos
 * environment of §5, it runs on the compose stack with its {@code chaos} profile.
 */
public final class Chaos {

    /** The JUnit tag of the scenarios: excluded from the other runs, the only one of the chaos run. */
    public static final String TAG = "chaos";

    private Chaos() {}

    /** Whether this test JVM is the chaos run. */
    public static boolean enabled() {
        return Boolean.getBoolean("tpe.chaos");
    }
}
