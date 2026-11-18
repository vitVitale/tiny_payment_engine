package com.tpe.systemtests.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The harness's processes as scrape targets of the local Alloy (specs/26-observability.md §13, issue #126): a
 * Prometheus file-discovery list at {@link #FILE}, which deploy/observability/alloy/modules/compose.alloy reads
 * through the compose stack's read-only mount of the repository. Each process is reached on the Docker host at its
 * port and labelled as the compose module labels a container: {@code namespace} the compose project, {@code pod} and
 * {@code instance} its name.
 */
final class AlloyTargets {

    /** Where {@link ServiceProcess} keeps the list, relative to the module (the test JVM's working directory). */
    static final Path FILE = Path.of("target", "alloy-targets.json");

    /** The compose project the stack runs as: the {@code namespace} of its containers' series. */
    private static final String NAMESPACE = "tpe";
    private static final String DOCKER_HOST = "host.docker.internal";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AlloyTargets() {}

    /** The list for the processes running now, by name and port, in that order. */
    static String render(Map<String, Integer> running) {
        List<Map<String, Object>> targets = new ArrayList<>();
        running.forEach((name, port) -> targets.add(Map.of(
                "targets", List.of(DOCKER_HOST + ":" + port),
                "labels", Map.of("namespace", NAMESPACE, "pod", name, "instance", name))));
        return JSON.writeValueAsString(targets);
    }

    /** Replaces the list with the processes running now: written aside, then moved, so Alloy never reads half of it. */
    static void write(Map<String, Integer> running) {
        try {
            Files.createDirectories(FILE.getParent());
            Path next = FILE.resolveSibling(FILE.getFileName() + ".next");
            Files.writeString(next, render(running));
            Files.move(next, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + FILE, e);
        }
    }
}
