package com.tpe.systemtests.harness;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.awaitility.core.ConditionTimeoutException;

/**
 * ledger-service as a process of this JVM's Java, from its built jar, in profiles {@code local} (the compose stack of
 * specs/00-engineering-baseline.md §13, test funding on) and {@code systemtest} (the short intervals of
 * specs/20-testing-strategy.md §2.1), on a free port. Started once per test JVM and stopped when it exits; its log goes
 * to {@code target/ledger-service-systemtest.log}.
 *
 * <p>The jar is {@code services/ledger-service/target/ledger-service-<version>.jar}, built earlier in the same reactor
 * run ({@code ./mvnw verify}) or by {@code ./mvnw -pl services/ledger-service -am package -DskipTests}; system
 * property {@code tpe.ledger-service.jar} points elsewhere.
 */
public final class LedgerService {

    /** Spring Boot startup with Flyway on a cold JVM; not a business time, so not in the profile. */
    private static final Duration STARTUP = Duration.ofSeconds(90);
    private static final Path LOG = Path.of("target", "ledger-service-systemtest.log");

    private static LedgerService instance;

    private final URI baseUrl;

    private LedgerService(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** The running service, started on first use. */
    public static synchronized LedgerService get() {
        if (instance == null) {
            instance = start();
        }
        return instance;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    private static LedgerService start() {
        Path jar = jar();
        int port = freePort();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process;
        try {
            Files.createDirectories(LOG.getParent());
            process = new ProcessBuilder(List.of(java, "-jar", jar.toString(),
                    "--spring.profiles.active=local,systemtest", "--server.port=" + port))
                    .redirectErrorStream(true)
                    .redirectOutput(LOG.toFile())
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start " + jar, e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
        var service = new LedgerService(URI.create("http://localhost:" + port));
        service.awaitReady(process);
        return service;
    }

    private void awaitReady(Process process) {
        var readiness = HttpRequest.newBuilder(baseUrl.resolve("/actuator/health/readiness")).GET().build();
        try (var http = HttpClient.newHttpClient()) {
            await().atMost(STARTUP).pollInterval(Duration.ofMillis(250)).ignoreExceptionsInstanceOf(IOException.class)
                    .until(() -> {
                        if (!process.isAlive()) {
                            throw new IllegalStateException("ledger-service exited with " + process.exitValue()
                                    + "; see " + LOG.toAbsolutePath());
                        }
                        return http.send(readiness, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                    });
        } catch (ConditionTimeoutException e) {
            process.destroy();
            throw new IllegalStateException("ledger-service not ready within " + STARTUP + "; see "
                    + LOG.toAbsolutePath(), e);
        }
    }

    private static Path jar() {
        String configured = System.getProperty("tpe.ledger-service.jar");
        Path jar;
        if (configured != null) {
            jar = Path.of(configured);
        } else {
            try (var jars = Files.list(Path.of("../../services/ledger-service/target"))) {
                jar = jars.filter(p -> p.getFileName().toString().matches("ledger-service-.*\\.jar"))
                        .findFirst().orElse(null);
            } catch (IOException e) {
                jar = null;
            }
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalStateException("no ledger-service jar; build it with "
                    + "./mvnw -pl services/ledger-service -am package -DskipTests or set -Dtpe.ledger-service.jar");
        }
        return jar.toAbsolutePath().normalize();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
