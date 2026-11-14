package com.tpe.systemtests.harness;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import org.testcontainers.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Toxiproxy (Testcontainers) in front of payment-orchestrator, for the payment-api that E2E-O-16 calls
 * (specs/20-testing-strategy.md §4): the proxy reaches the orchestrator on the host, and {@link #uri()} is where that
 * payment-api sends its submits. Started once per test JVM, on first use, with no toxic.
 */
public final class OrchestratorProxy {

    private static final String IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";
    private static final int LISTEN = 8666;
    private static final String LOST_RESPONSE = "lost-response";

    private static OrchestratorProxy started;

    private final Proxy proxy;
    private final URI uri;

    private OrchestratorProxy(Proxy proxy, URI uri) {
        this.proxy = proxy;
        this.uri = uri;
    }

    /** The proxy in front of the orchestrator at {@code orchestrator}, started on first use. */
    public static synchronized OrchestratorProxy of(URI orchestrator) {
        if (started == null) {
            Testcontainers.exposeHostPorts(orchestrator.getPort());
            var container = new ToxiproxyContainer(IMAGE);
            container.start();
            var client = new ToxiproxyClient(container.getHost(), container.getControlPort());
            try {
                Proxy proxy = client.createProxy("orchestrator", "0.0.0.0:" + LISTEN,
                        "host.testcontainers.internal:" + orchestrator.getPort());
                started = new OrchestratorProxy(proxy,
                        URI.create("http://" + container.getHost() + ":" + container.getMappedPort(LISTEN)));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create the orchestrator proxy", e);
            }
        }
        return started;
    }

    /** Where the proxied payment-api calls the orchestrator. */
    public URI uri() {
        return uri;
    }

    /**
     * Every response of the orchestrator from now on arrives {@code delay} late, so a caller whose timeout is shorter
     * loses it; the requests themselves get through at once (UC-01 6a: the orchestrator was reached).
     */
    public void delayResponses(Duration delay) {
        try {
            proxy.toxics().latency(LOST_RESPONSE, ToxicDirection.DOWNSTREAM, delay.toMillis());
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
