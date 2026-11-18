package com.tpe.systemtests.harness;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Toxiproxy (Testcontainers) in front of a service on the host, for a caller that a test slows down
 * (specs/20-testing-strategy.md §4, §5): the payment-api of E2E-O-16 calling payment-orchestrator, the second
 * csm-gateway of CH-02 calling the CSM. {@link #uri()} is where the caller sends its requests. One container per test
 * JVM, started on first use; one proxy per name, with no toxic.
 */
public final class HostProxy {

    private static final String IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";
    /** The first of the ports {@link ToxiproxyContainer} exposes for proxies. */
    private static final int FIRST_LISTEN = 8666;
    private static final String SLOW_RESPONSES = "slow-responses";

    private static ToxiproxyContainer container;
    private static ToxiproxyClient client;
    private static final Map<String, HostProxy> PROXIES = new HashMap<>();

    private final Proxy proxy;
    private final URI uri;

    private HostProxy(Proxy proxy, URI uri) {
        this.proxy = proxy;
        this.uri = uri;
    }

    /** The proxy {@code name} in front of the service at {@code target}, created on first use. */
    public static synchronized HostProxy of(String name, URI target) {
        return PROXIES.computeIfAbsent(name, created -> {
            // before the container's start for the first: it gets host.testcontainers.internal then
            Testcontainers.exposeHostPorts(target.getPort());
            if (container == null) {
                container = new ToxiproxyContainer(IMAGE);
                container.start();
                client = new ToxiproxyClient(container.getHost(), container.getControlPort());
            }
            int listen = FIRST_LISTEN + PROXIES.size();
            try {
                Proxy proxy = client.createProxy(name, "0.0.0.0:" + listen,
                        "host.testcontainers.internal:" + target.getPort());
                return new HostProxy(proxy,
                        URI.create("http://" + container.getHost() + ":" + container.getMappedPort(listen)));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create the proxy " + name, e);
            }
        });
    }

    /** Where the proxied caller sends its requests. */
    public URI uri() {
        return uri;
    }

    /**
     * Every response from now on arrives {@code delay} late, so a caller whose timeout is shorter loses it; the
     * requests themselves get through at once (UC-01 6a: the service was reached).
     */
    public void delayResponses(Duration delay) {
        try {
            proxy.toxics().latency(SLOW_RESPONSES, ToxicDirection.DOWNSTREAM, delay.toMillis());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Responses pass at once again; does nothing without a toxic. */
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
