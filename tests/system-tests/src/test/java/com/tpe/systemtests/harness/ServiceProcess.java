package com.tpe.systemtests.harness;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionTimeoutException;

/**
 * A service as a process of this JVM's Java, from its jar ({@link ServiceJar}), in profiles {@code local} (the compose
 * stack of specs/00-engineering-baseline.md §13) and {@code systemtest} (the short intervals of
 * specs/20-testing-strategy.md §2.1), on a free port or one reserved before. Started once per test JVM, on first use,
 * and stopped when the JVM exits; its output goes to {@code target/<name>-systemtest.log}. A test may {@link #stop}
 * it and {@link #start} it again: the same port and properties, the log appended to.
 */
public final class ServiceProcess {

    /** The profiles every service runs in under the system tests (20 §2.1); {@link ServiceProfile} reads the same. */
    static final List<String> PROFILES = profiles(Chaos.enabled());

    /**
     * {@code local,systemtest}; for the chaos run {@code local} only, so the services keep their default timings (20
     * §5: the real margins absorb the failure).
     */
    static List<String> profiles(boolean chaos) {
        return chaos ? List.of("local") : List.of("local", "systemtest");
    }

    /** Spring Boot startup with Flyway on a cold JVM; not a business time, so not in the profile. */
    private static final Duration STARTUP = Duration.ofSeconds(90);

    /** A graceful shutdown: the service's own drain, not a business time. */
    private static final Duration SHUTDOWN = Duration.ofSeconds(30);

    private static final Map<String, ServiceProcess> RUNNING = new HashMap<>();

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private final String name;
    private final ServiceJar service;
    private final Map<String, String> properties;
    private final int port;
    private final URI baseUrl;
    private final Path log;
    private Process process;

    private ServiceProcess(String name, ServiceJar service, Map<String, String> properties, int port) {
        this.name = name;
        this.service = service;
        this.properties = new TreeMap<>(properties);
        this.port = port;
        this.baseUrl = URI.create("http://localhost:" + port);
        this.log = Path.of("target", name + "-systemtest.log");
    }

    /** The running {@code service}, started on first use. */
    public static synchronized ServiceProcess of(ServiceJar service) {
        return of(service, Map.of());
    }

    /**
     * The running {@code service}, started on first use with {@code properties} as {@code --key=value} arguments after
     * the profiles: how a service learns the random ports of the services it calls. A later call gets the running
     * process whatever properties it gives, so a service's neighbours are started before it.
     */
    public static synchronized ServiceProcess of(ServiceJar service, Map<String, String> properties) {
        return of(service.name(), service, properties, 0);
    }

    /**
     * As {@link #of(ServiceJar, Map)} on {@code port}, reserved with {@link #freePort} before: for two services that
     * call each other, one is told the other's port before that one starts.
     */
    public static synchronized ServiceProcess of(ServiceJar service, Map<String, String> properties, int port) {
        return of(service.name(), service, properties, port);
    }

    /**
     * As {@link #of(ServiceJar, Map)} for a further process of {@code service}'s jar, known by {@code instance}: its own
     * port and its own log, {@code target/<instance>-systemtest.log}.
     */
    public static synchronized ServiceProcess of(String instance, ServiceJar service, Map<String, String> properties) {
        return of(instance, service, properties, 0);
    }

    private static ServiceProcess of(String instance, ServiceJar service, Map<String, String> properties, int port) {
        return RUNNING.computeIfAbsent(instance, name -> {
            var started = new ServiceProcess(name, service, properties, port == 0 ? freePort() : port);
            started.start();
            return started;
        });
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Stops the process as a pod is stopped (SIGTERM where the OS has it; on Windows the process is terminated), and
     * waits until it has exited.
     */
    public synchronized void stop() {
        if (!isRunning()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(SHUTDOWN.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Kills the process at once ({@code SIGKILL}; on Windows it is terminated) and waits until it has gone: no shutdown
     * of its own, so what it claimed stays claimed until its leases run out (CH-02).
     */
    public synchronized void kill() {
        if (!isRunning()) {
            return;
        }
        try {
            process.destroyForcibly().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Suspends the running process: every thread stops, sockets and leases are kept, nothing is answered or consumed
     * until {@link #thaw}. A service down for less than its cold start (E2E-O-12: the 3 s between {@code sendBy} and
     * the deadline), which {@link #stop} and {@link #start} cannot give. {@code SIGSTOP}, on Windows
     * {@code NtSuspendProcess}.
     */
    public synchronized void freeze() {
        signal("STOP", "NtSuspendProcess");
    }

    /** Resumes a {@link #freeze frozen} process where it stopped. {@code SIGCONT}, on Windows {@code NtResumeProcess}. */
    public synchronized void thaw() {
        signal("CONT", "NtResumeProcess");
    }

    private void signal(String unixSignal, String windowsCall) {
        if (!isRunning()) {
            throw new IllegalStateException(name + " is not running");
        }
        long pid = process.pid();
        // encoded: the quotes of the script would not survive Windows' command line parsing
        String script = """
                Add-Type -Namespace Tpe -Name Nt -MemberDefinition '[DllImport("ntdll.dll")] public static \
                extern int %1$s(IntPtr handle);'; $p = [Diagnostics.Process]::GetProcessById(%2$d); \
                exit [Tpe.Nt]::%1$s($p.Handle)""".formatted(windowsCall, pid);
        List<String> command = WINDOWS
                ? List.of("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                        Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                : List.of("kill", "-" + unixSignal, Long.toString(pid));
        try {
            Process signalled = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(signalled.getInputStream().readAllBytes());
            if (signalled.waitFor() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot signal " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Starts the process unless it runs, and waits until it is ready: its port and properties of the first start. */
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        Path jar = service.locate();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try {
            Files.createDirectories(log.getParent());
            var command = new ArrayList<>(List.of(java, "-jar", jar.toString(),
                    "--spring.profiles.active=" + String.join(",", PROFILES), "--server.port=" + port));
            properties.forEach((key, value) -> command.add("--" + key + "=" + value));
            boolean restart = process != null;
            if (!restart) {
                Files.deleteIfExists(log);
            }
            Files.writeString(log, (restart ? "\n==== restarted\n" : ""), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start " + jar, e);
        }
        Process started = process;
        Runtime.getRuntime().addShutdownHook(new Thread(started::destroy));
        awaitReady(started);
    }

    private void awaitReady(Process started) {
        var readiness = HttpRequest.newBuilder(baseUrl.resolve("/actuator/health/readiness")).GET().build();
        try (var http = HttpClient.newHttpClient()) {
            await().atMost(STARTUP).pollInterval(Duration.ofMillis(250)).ignoreExceptionsInstanceOf(IOException.class)
                    .until(() -> {
                        if (!started.isAlive()) {
                            throw new IllegalStateException(name + " exited with " + started.exitValue()
                                    + "; see " + log.toAbsolutePath());
                        }
                        return http.send(readiness, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                    });
        } catch (ConditionTimeoutException e) {
            started.destroy();
            throw new IllegalStateException(name + " not ready within " + STARTUP + "; see " + log.toAbsolutePath(),
                    e);
        }
    }

    /** A port free now, to start a service on later ({@link #of(ServiceJar, Map, int)}). */
    public static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
