package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
