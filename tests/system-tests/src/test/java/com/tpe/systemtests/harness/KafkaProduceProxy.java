package com.tpe.systemtests.harness;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The compose stack's Toxiproxy of profile {@code chaos} between the payment-orchestrators' producers and Kafka (CH-03,
 * specs/20-testing-strategy.md §5): {@code localhost:19094} to Kafka's {@code CHAOS} listener, which Kafka advertises
 * at that same address, so a producer that bootstraps there sends everything through it. The orchestrators consume
 * from {@code localhost:9092} directly: blocking the proxy blocks their produce only.
 */
public final class KafkaProduceProxy {

    /** Where a proxied producer bootstraps: the proxy's fixed port, as Kafka advertises it. */
    public static final String BOOTSTRAP = "localhost:19094";

    private static final String NAME = "kafka-produce";
    private static final String CONTROL_HOST = "localhost";
    private static final int CONTROL_PORT = 8474;
    private static final String LISTEN = "0.0.0.0:19094";
    private static final String UPSTREAM = "kafka:9094";

    private static KafkaProduceProxy started;

    private final Proxy proxy;

    private KafkaProduceProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    /**
     * The proxy, created on first use, or taken over from an earlier run (the compose container outlives a test JVM),
     * then enabled and without toxics.
     */
    public static synchronized KafkaProduceProxy get() {
        if (started == null) {
            var client = new ToxiproxyClient(CONTROL_HOST, CONTROL_PORT);
            try {
                Proxy proxy = client.getProxyOrNull(NAME);
                if (proxy == null) {
                    proxy = client.createProxy(NAME, LISTEN, UPSTREAM);
                }
                for (var toxic : proxy.toxics().getAll()) {
                    toxic.remove();
                }
                proxy.enable();
                started = new KafkaProduceProxy(proxy);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot reach the chaos toxiproxy on " + CONTROL_HOST + ":"
                        + CONTROL_PORT + "; start it: docker compose -f deploy/docker-compose.yml --profile chaos up -d "
                        + "toxiproxy", e);
            }
        }
        return started;
    }

    /** Kafka unreachable for produce: open connections are closed, new ones refused. */
    public void block() {
        try {
            proxy.disable();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Kafka reachable for produce again. */
    public void unblock() {
        try {
            proxy.enable();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
