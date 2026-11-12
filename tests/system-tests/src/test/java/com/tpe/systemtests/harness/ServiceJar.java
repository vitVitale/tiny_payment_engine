package com.tpe.systemtests.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A service the harness can start ({@link ServiceProcess}): its name and the directory its jar is built in, the first
 * {@code <name>-*.jar} there. System property {@code tpe.<name>.jar} points at another jar.
 *
 * @param name the service's Maven {@code artifactId}, which also names its log and its jar property
 * @param directory where the build puts the jar
 */
public record ServiceJar(String name, Path directory) {

    /** The service modules of this repository, from the system-tests module. */
    private static final Path SERVICES = Path.of("../../services");

    /** ledger-service, the harness's first user. */
    public static final ServiceJar LEDGER = inRepository("ledger-service");

    /**
     * A service of this repository: its jar in {@code services/<name>/target}, built earlier in the same reactor run
     * ({@code ./mvnw verify}) or by {@code ./mvnw -pl services/<name> -am package -DskipTests}.
     */
    public static ServiceJar inRepository(String name) {
        return new ServiceJar(name, SERVICES.resolve(name).resolve("target"));
    }

    /** The configuration sources of a service of this repository ({@link ServiceProfile} reads them). */
    Path resources() {
        return SERVICES.resolve(name).resolve("src/main/resources");
    }

    /** The jar to start: {@code tpe.<name>.jar} if set, else the first {@code <name>-*.jar} in {@link #directory}. */
    public Path locate() {
        String configured = System.getProperty(property());
        Path jar = configured != null ? Path.of(configured) : findIn(directory);
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalStateException("no " + name + " jar; build it with ./mvnw -pl services/" + name
                    + " -am package -DskipTests or set -D" + property());
        }
        return jar.toAbsolutePath().normalize();
    }

    private String property() {
        return "tpe." + name + ".jar";
    }

    private Path findIn(Path directory) {
        var jarName = Pattern.compile(Pattern.quote(name) + "-.*\\.jar");
        try (var files = Files.list(directory)) {
            return files.filter(p -> jarName.matcher(p.getFileName().toString()).matches()).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
