package com.tpe.systemtests.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * The configuration a service runs with in the system tests: its {@code application.yml} overlaid by the profiles the
 * harness starts it in, later files winning, as Spring Boot resolves them. Read from the service's sources, so a bound
 * derived from it follows the profile (specs/20-testing-strategy.md §2).
 */
public final class ServiceProfile {

    private static final Pattern SIMPLE_DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    private final List<Map<String, Object>> layers;

    ServiceProfile(List<Map<String, Object>> layers) {
        this.layers = List.copyOf(layers);
    }

    /** ledger-service as the harness runs it. */
    public static ServiceProfile ledger() {
        return systemtest(ServiceJar.LEDGER);
    }

    /** A service of this repository in the profiles {@link ServiceProcess} starts it in ({@code local,systemtest}). */
    public static ServiceProfile systemtest(ServiceJar service) {
        return of(service.resources(), ServiceProcess.PROFILES.toArray(String[]::new));
    }

    static ServiceProfile of(Path resources, String... profiles) {
        var layers = new ArrayList<Map<String, Object>>();
        layers.add(load(resources.resolve("application.yml")));
        for (String profile : profiles) {
            layers.add(load(resources.resolve("application-" + profile + ".yml")));
        }
        return new ServiceProfile(layers);
    }

    /** The duration at the dotted {@code key}, from the last layer that sets it. */
    public Optional<Duration> duration(String key) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Object value = lookup(layers.get(i), key);
            if (value != null) {
                return Optional.of(parse(value.toString()));
            }
        }
        return Optional.empty();
    }

    private static Object lookup(Map<String, Object> tree, String key) {
        Object node = tree;
        for (String part : key.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(part);
        }
        return node instanceof Map<?, ?> ? null : node;
    }

    /** Spring Boot's simple format ({@code 500ms}, {@code 5s}, …) or ISO-8601. */
    static Duration parse(String value) {
        var simple = SIMPLE_DURATION.matcher(value.trim());
        if (!simple.matches()) {
            return Duration.parse(value.trim());
        }
        long amount = Long.parseLong(simple.group(1));
        return switch (simple.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    private static Map<String, Object> load(Path yaml) {
        if (!Files.isRegularFile(yaml)) {
            return Map.of();
        }
        try {
            Map<String, Object> tree = new Yaml().load(Files.readString(yaml));
            return tree == null ? Map.of() : tree;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
