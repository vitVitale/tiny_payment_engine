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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.core.ConditionTimeoutException;

/**
 * A service as a process of this JVM's Java, from its jar ({@link ServiceJar}), in profiles {@code local} (the compose
 * stack of specs/00-engineering-baseline.md §13) and {@code systemtest} (the short intervals of
 * specs/20-testing-strategy.md §2.1), on a free port. Started once per test JVM, on first use, and stopped when the JVM
 * exits; its output goes to {@code target/<name>-systemtest.log}.
 */
public final class ServiceProcess {

    /** The profiles every service runs in under the system tests (20 §2.1); {@link ServiceProfile} reads the same. */
    static final List<String> PROFILES = List.of("local", "systemtest");

    /** Spring Boot startup with Flyway on a cold JVM; not a business time, so not in the profile. */
    private static final Duration STARTUP = Duration.ofSeconds(90);

    private static final Map<String, ServiceProcess> RUNNING = new HashMap<>();

    private final String name;
    private final URI baseUrl;
    private final Path log;

    private ServiceProcess(String name, URI baseUrl, Path log) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.log = log;
    }

    /** The running {@code service}, started on first use. */
    public static synchronized ServiceProcess of(ServiceJar service) {
        return RUNNING.computeIfAbsent(service.name(), name -> start(service));
    }

    public URI baseUrl() {
        return baseUrl;
    }

    private static ServiceProcess start(ServiceJar service) {
        Path jar = service.locate();
        int port = freePort();
        Path log = Path.of("target", service.name() + "-systemtest.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process;
        try {
            Files.createDirectories(log.getParent());
            process = new ProcessBuilder(List.of(java, "-jar", jar.toString(),
                    "--spring.profiles.active=" + String.join(",", PROFILES), "--server.port=" + port))
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start " + jar, e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
        var started = new ServiceProcess(service.name(), URI.create("http://localhost:" + port), log);
        started.awaitReady(process);
        return started;
    }

    private void awaitReady(Process process) {
        var readiness = HttpRequest.newBuilder(baseUrl.resolve("/actuator/health/readiness")).GET().build();
        try (var http = HttpClient.newHttpClient()) {
            await().atMost(STARTUP).pollInterval(Duration.ofMillis(250)).ignoreExceptionsInstanceOf(IOException.class)
                    .until(() -> {
                        if (!process.isAlive()) {
                            throw new IllegalStateException(name + " exited with " + process.exitValue()
                                    + "; see " + log.toAbsolutePath());
                        }
                        return http.send(readiness, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                    });
        } catch (ConditionTimeoutException e) {
            process.destroy();
            throw new IllegalStateException(name + " not ready within " + STARTUP + "; see " + log.toAbsolutePath(),
                    e);
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
