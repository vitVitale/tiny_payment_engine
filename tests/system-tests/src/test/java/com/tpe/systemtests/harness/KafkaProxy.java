package com.tpe.systemtests.harness;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * A proxy of the compose stack's Toxiproxy (profile {@code chaos}) between some of the services' Kafka clients and
 * Kafka (specs/20-testing-strategy.md §4, §5). Each goes to a listener of its own, which Kafka advertises at the
 * proxy's fixed port, so a client that bootstraps there keeps talking through it; the client's other side goes to
 * {@code localhost:9092} directly, so cutting or slowing a proxy affects that one side. Without a toxic it is
 * transparent.
 *
 * <ul>
 *   <li>{@link #orchestratorProduce()}: the payment-orchestrators' producers, in every system-test run. CH-03 blocks
 *       it; E2E-I-06 slows it past the answer deadline.
 *   <li>{@link #gatewayConsume()}: the csm-gateways' consumers, in the chaos run. CH-04 cuts it off past the answer
 *       deadline.
 * </ul>
 */
public final class KafkaProxy {

    private static final String CONTROL_HOST = "localhost";
    private static final int CONTROL_PORT = 8474;
    private static final String SLOW = "slow";
    private static final Map<String, KafkaProxy> STARTED = new HashMap<>();

    private final Proxy proxy;
    private final String bootstrap;

    private KafkaProxy(Proxy proxy, String bootstrap) {
        this.proxy = proxy;
        this.bootstrap = bootstrap;
    }

    /** {@code localhost:19094} to Kafka's {@code CHAOS} listener: what the orchestrators produce through. */
    public static KafkaProxy orchestratorProduce() {
        return get("kafka-produce", 19094, "kafka:9094");
    }

    /** {@code localhost:19095} to Kafka's {@code CHAOS_CONSUME} listener: what the chaos run's gateways consume through. */
    public static KafkaProxy gatewayConsume() {
        return get("kafka-gateway-consume", 19095, "kafka:9095");
    }

    /**
     * The proxy {@code name}, created on first use, or taken over from an earlier run (the compose container outlives
     * a test JVM), then enabled and without toxics.
     */
    private static synchronized KafkaProxy get(String name, int port, String upstream) {
        return STARTED.computeIfAbsent(name, created -> {
            var client = new ToxiproxyClient(CONTROL_HOST, CONTROL_PORT);
            try {
                Proxy proxy = client.getProxyOrNull(name);
                if (proxy == null) {
                    proxy = client.createProxy(name, "0.0.0.0:" + port, upstream);
                }
                for (var toxic : proxy.toxics().getAll()) {
                    toxic.remove();
                }
                proxy.enable();
                return new KafkaProxy(proxy, "localhost:" + port);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot reach the toxiproxy of profile chaos on " + CONTROL_HOST
                        + ":" + CONTROL_PORT + ", which the system tests need; start it: docker compose -f "
                        + "deploy/docker-compose.yml --profile chaos up -d toxiproxy", e);
            }
        });
    }

    /** Where a proxied client bootstraps: the proxy's fixed port, as Kafka advertises it. */
    public String bootstrap() {
        return bootstrap;
    }

    /** Kafka unreachable on this side: open connections are closed, new ones refused. */
    public void block() {
        try {
            proxy.disable();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Kafka reachable on this side again. */
    public void unblock() {
        try {
            proxy.enable();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Everything the clients send through the proxy from now on reaches Kafka {@code delay} late, until {@link #heal()}. */
    public void delayRequests(Duration delay) {
        try {
            proxy.toxics().latency(SLOW, ToxicDirection.UPSTREAM, delay.toMillis());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Requests pass at once again; does nothing without a toxic. */
    public void heal() {
        try {
            for (var toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
