package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/**
 * The generic starter (issue #63): a service is its name and its jar; the harness starts it once per JVM in profiles
 * {@code local,systemtest} on a free port, waits for readiness and logs to {@code target/<name>-systemtest.log}.
 * {@link FakeService} stands in for a business service.
 */
class ServiceProcessTest {

    /** Not a {@code @TempDir}: the started processes hold their jars until the JVM exits, which Windows won't delete. */
    private static final Path jars = Path.of("target", "fake-services");

    @Test
    void aServiceRegisteredByNameAndJarDirectoryStartsInTheSystemTestProfilesOnAFreePortOnce() throws Exception {
        var service = new ServiceJar("fake-service-a", fakeJar(jars.resolve("a"), "fake-service-a-1.0.jar").getParent());

        ServiceProcess process = ServiceProcess.of(service);

        assertThat(process.baseUrl().getPort()).isPositive();
        assertThat(get(process, "/args"))
                .contains("--spring.profiles.active=local,systemtest")
                .contains("--server.port=" + process.baseUrl().getPort());
        assertThat(Files.readString(Path.of("target", "fake-service-a-systemtest.log")))
                .contains("fake service started with");
        assertThat(ServiceProcess.of(service)).isSameAs(process);
    }

    @Test
    void theJarPropertyOfTheServiceWinsOverItsDirectory() throws Exception {
        Path jar = fakeJar(jars.resolve("elsewhere"), "anything.jar");
        System.setProperty("tpe.fake-service-b.jar", jar.toString());
        try {
            var service = new ServiceJar("fake-service-b", jars.resolve("empty"));

            assertThat(service.locate()).isEqualTo(jar.toAbsolutePath().normalize());
            assertThat(get(ServiceProcess.of(service), "/args")).contains("--server.port=");
        } finally {
            System.clearProperty("tpe.fake-service-b.jar");
        }
    }

    /** How a service learns its neighbours' random ports: as properties on its command line, after the profiles. */
    @Test
    void propertiesGivenAtTheFirstStartAreCommandLineArguments() throws Exception {
        var service = new ServiceJar("fake-service-c", fakeJar(jars.resolve("c"), "fake-service-c-1.0.jar").getParent());

        ServiceProcess process = ServiceProcess.of(service,
                Map.of("tpe.clients.ledger.base-url", "http://localhost:4711"));

        assertThat(get(process, "/args")).contains("--tpe.clients.ledger.base-url=http://localhost:4711");
        assertThat(ServiceProcess.of(service)).isSameAs(process);
    }

    /** E2E-O-16: a second payment-api, calling the orchestrator through a proxy, beside the one every test uses. */
    @Test
    void aNamedInstanceIsASecondProcessOfTheSameJarWithItsOwnLog() throws Exception {
        var service = new ServiceJar("fake-service-d", fakeJar(jars.resolve("d"), "fake-service-d-1.0.jar").getParent());

        ServiceProcess first = ServiceProcess.of(service);
        ServiceProcess second = ServiceProcess.of("fake-service-d-proxied", service, Map.of("tpe.x", "y"));

        assertThat(second).isNotSameAs(first);
        assertThat(second.baseUrl().getPort()).isNotEqualTo(first.baseUrl().getPort());
        assertThat(get(second, "/args")).contains("--tpe.x=y");
        assertThat(Path.of("target", "fake-service-d-proxied-systemtest.log")).exists();
        assertThat(ServiceProcess.of("fake-service-d-proxied", service, Map.of())).isSameAs(second);
    }

    /** csm-gateway and csm-simulator call each other: one's port is known before the other starts. */
    @Test
    void aServiceStartsOnAPortReservedBeforeHand() throws Exception {
        var service = new ServiceJar("fake-service-e", fakeJar(jars.resolve("e"), "fake-service-e-1.0.jar").getParent());
        int port = ServiceProcess.freePort();

        ServiceProcess process = ServiceProcess.of(service, Map.of(), port);

        assertThat(process.baseUrl().getPort()).isEqualTo(port);
        assertThat(get(process, "/args")).contains("--server.port=" + port);
    }

    /** csm-gateway down and back (E2E-O-12 and later): the same port and properties, a fresh log section. */
    @Test
    void aStoppedServiceStartsAgainOnItsPortWithItsProperties() throws Exception {
        var service = new ServiceJar("fake-service-f", fakeJar(jars.resolve("f"), "fake-service-f-1.0.jar").getParent());
        ServiceProcess process = ServiceProcess.of(service, Map.of("tpe.x", "y"));

        process.stop();

        assertThat(process.isRunning()).isFalse();
        assertThatThrownBy(() -> get(process, "/args")).isInstanceOf(IOException.class);

        process.start();

        assertThat(process.isRunning()).isTrue();
        assertThat(get(process, "/args")).contains("--tpe.x=y", "--server.port=" + process.baseUrl().getPort());
        assertThat(ServiceProcess.of(service)).isSameAs(process);
    }

    /**
     * csm-gateway down for less than a cold start (E2E-O-12): a frozen process keeps its port and answers nothing until
     * it is thawed, then carries on as it was.
     */
    @Test
    void aFrozenServiceAnswersNothingUntilItIsThawed() throws Exception {
        var service = new ServiceJar("fake-service-g", fakeJar(jars.resolve("g"), "fake-service-g-1.0.jar").getParent());
        ServiceProcess process = ServiceProcess.of(service);

        process.freeze();
        try {
            assertThat(process.isRunning()).isTrue();
            assertThatThrownBy(() -> get(process, "/args", Duration.ofMillis(500)))
                    .isInstanceOf(HttpTimeoutException.class);
        } finally {
            process.thaw();
        }

        assertThat(get(process, "/args", Duration.ofSeconds(5))).contains("--server.port=");
    }

    /** CH-02: a gateway killed mid-send leaves its claims to the other instance, then starts again. */
    @Test
    void aKilledServiceIsGoneAtOnceAndStartsAgain() throws Exception {
        var service = new ServiceJar("fake-service-h", fakeJar(jars.resolve("h"), "fake-service-h-1.0.jar").getParent());
        ServiceProcess process = ServiceProcess.of(service);

        process.kill();

        assertThat(process.isRunning()).isFalse();
        assertThatThrownBy(() -> get(process, "/args")).isInstanceOf(IOException.class);
        process.start();
        assertThat(get(process, "/args")).contains("--server.port=");
    }

    /** OBS-AC-01 with a real payment (#126): the local Alloy scrapes what runs now, and nothing that has stopped. */
    @Test
    void theAlloyTargetsListFollowsTheRunningProcesses() throws Exception {
        var service = new ServiceJar("fake-service-i", fakeJar(jars.resolve("i"), "fake-service-i-1.0.jar").getParent());
        ServiceProcess process = ServiceProcess.of(service);
        String target = "host.docker.internal:" + process.baseUrl().getPort();

        assertThat(Files.readString(AlloyTargets.FILE)).contains(target, "\"fake-service-i\"");

        process.stop();
        assertThat(Files.readString(AlloyTargets.FILE)).doesNotContain(target);
        process.start();
        assertThat(Files.readString(AlloyTargets.FILE)).contains(target);
    }

    /** With the observability stack, a service exports its spans to the agent's published OTLP port. */
    @Test
    void withTheObservabilityStackEveryServiceExportsItsSpansToTheAgent() {
        assertThat(ServiceProcess.observabilityProperties(true))
                .containsExactly(Map.entry("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317"));
        assertThat(ServiceProcess.observabilityProperties(false)).isEmpty();
    }

    /** The chaos run (20 §5) uses the default timings: no {@code systemtest} profile. */
    @Test
    void theChaosRunStartsServicesInTheLocalProfileOnly() {
        assertThat(ServiceProcess.profiles(false)).containsExactly("local", "systemtest");
        assertThat(ServiceProcess.profiles(true)).containsExactly("local");
    }

    @Test
    void aServiceWithoutAJarNamesTheBuildCommandAndTheProperty() {
        var service = ServiceJar.inRepository("no-such-service");

        assertThatThrownBy(service::locate)
                .hasMessageContaining("./mvnw -pl services/no-such-service -am package -DskipTests")
                .hasMessageContaining("-Dtpe.no-such-service.jar");
    }

    @Test
    void aRepositoryServiceIsLookedUpInItsModulesTarget() {
        assertThat(ServiceJar.inRepository("ledger-service").directory())
                .isEqualTo(Path.of("../../services/ledger-service/target"));
    }

    private static String get(ServiceProcess process, String path) throws IOException, InterruptedException {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(process.baseUrl().resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private static String get(ServiceProcess process, String path, Duration timeout)
            throws IOException, InterruptedException {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(process.baseUrl().resolve(path)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    /** {@link FakeService} as a runnable jar named {@code name} in {@code directory}. */
    private static Path fakeJar(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeService.class.getName());
        String entry = FakeService.class.getName().replace('.', '/') + ".class";
        Path jar = directory.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest);
                InputStream classFile = FakeService.class.getClassLoader().getResourceAsStream(entry)) {
            out.putNextEntry(new JarEntry(entry));
            classFile.transferTo(out);
            out.closeEntry();
        }
        return jar;
    }
}
