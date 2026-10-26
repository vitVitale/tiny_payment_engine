package com.tpe.traceabilitycheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The traceability check of specs/20-testing-strategy.md §1: every id of the specs has a test carrying it, unless it
 * is in the pending-ids baseline, which only shrinks.
 */
public final class TraceabilityCheck {

    /** The working tree: spec and test sources by path, and the pending-ids baseline. */
    record Snapshot(Map<String, String> specs, Map<String, String> tests, Set<String> pendingIds) {}

    /** Main at the merge base: its specs, and its baseline unless main has none yet (the bootstrap). */
    record Base(Map<String, String> specs, Optional<Set<String>> pendingIds) {}

    private static final String BASELINE = "tools/traceability-check/pending-ids.txt";

    private TraceabilityCheck() {}

    /**
     * Runs the check from the repository root against the working tree. The base is the merge base of {@code HEAD}
     * and {@code --base=<ref>} (default {@code origin/main}; e.g. {@code --base=main} in a clone without that remote
     * branch). Exit code 0 passes, 1 lists violations, 2 is a usage or git error.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        var baseRef = "origin/main";
        for (var arg : args) {
            if (arg.startsWith("--base=")) {
                baseRef = arg.substring("--base=".length());
            } else {
                exit("usage: TraceabilityCheck [--base=<git ref>]");
            }
        }
        var root = Path.of("");
        if (!Files.isDirectory(root.resolve("specs"))) {
            exit("run from the repository root (no specs/ directory here)");
        }
        var mergeBase = git(root, "merge-base", "HEAD", baseRef).map(String::strip);
        if (mergeBase.isEmpty()) {
            exit("no merge base of HEAD and " + baseRef + "; fetch main with its history: git fetch origin main");
        }
        var current = new Snapshot(readSpecs(root), readTests(root), readBaseline(root));
        var violations = check(current, readBase(root, mergeBase.get()));
        if (violations.isEmpty()) {
            System.out.printf("Traceability check passed (%d ids pending, see %s)%n", current.pendingIds().size(), BASELINE);
            return;
        }
        System.out.println("Traceability check failed (see the Traceability row of specs/20-testing-strategy.md):");
        violations.forEach(v -> System.out.println("  " + v.message()));
        System.exit(1);
    }

    static List<Violation> check(Snapshot current, Base base) {
        var violations = new ArrayList<Violation>();
        var required = SpecIds.required(current.specs());
        var carried = TestSources.scan(current.tests());
        var pending = current.pendingIds();
        required.forEach((id, kind) -> {
            var missing = carried.missing(id, kind);
            if (!pending.contains(id)) {
                missing.forEach(reason -> violations.add(new Violation(id, reason)));
            } else if (missing.isEmpty()) {
                violations.add(new Violation(id, Violation.Reason.PENDING_BUT_TESTED));
            }
        });
        for (var id : pending) {
            if (!required.containsKey(id)) {
                violations.add(new Violation(id, Violation.Reason.PENDING_BUT_NOT_IN_SPECS));
            }
        }
        // the baseline only shrinks; ids new to the specs may enter it
        base.pendingIds().ifPresent(basePending -> {
            var onMain = SpecIds.required(base.specs()).keySet();
            for (var id : pending) {
                if (!basePending.contains(id) && onMain.contains(id)) {
                    violations.add(new Violation(id, Violation.Reason.ADDED_BUT_ON_MAIN));
                }
            }
        });
        violations.sort(null);
        return violations;
    }

    private static Map<String, String> readSpecs(Path root) throws IOException {
        var specs = new TreeMap<String, String>();
        try (var files = Files.list(root.resolve("specs"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".md")).toList()) {
                specs.put(slashed(root.relativize(file)), Files.readString(file));
            }
        }
        return specs;
    }

    /** Java sources of every {@code src/test} and {@code src/integrationTest} tree outside build output. */
    private static Map<String, String> readTests(Path root) throws IOException {
        var tests = new TreeMap<String, String>();
        try (var files = Files.walk(root)) {
            files.map(root::relativize)
                    .map(TraceabilityCheck::slashed)
                    .filter(p -> p.endsWith(".java") && (p.contains("src/test/") || p.contains("src/integrationTest/")))
                    .filter(p -> !p.startsWith(".git/") && !p.contains("/target/"))
                    .forEach(p -> tests.put(p, readUnchecked(root.resolve(p))));
        }
        return tests;
    }

    private static Set<String> readBaseline(Path root) throws IOException {
        var file = root.resolve(BASELINE);
        return Files.exists(file) ? parseBaseline(Files.readString(file)) : Set.of();
    }

    private static Base readBase(Path root, String commit) throws IOException, InterruptedException {
        var specs = new TreeMap<String, String>();
        for (var path : git(root, "ls-tree", "--name-only", commit, "specs/").orElseThrow().lines().toList()) {
            if (path.endsWith(".md")) {
                specs.put(path, git(root, "show", commit + ":" + path).orElseThrow());
            }
        }
        return new Base(specs, git(root, "show", commit + ":" + BASELINE).map(TraceabilityCheck::parseBaseline));
    }

    /** One id per line; blank lines and {@code #} comments are ignored. */
    private static Set<String> parseBaseline(String text) {
        var ids = new TreeSet<String>();
        text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(ids::add);
        return ids;
    }

    /** Stdout of the git command, or empty when it exits non-zero (e.g. a path missing in that revision). */
    private static Optional<String> git(Path root, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command)
                .directory(root.toAbsolutePath().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return process.waitFor() == 0 ? Optional.of(out) : Optional.empty();
    }

    private static String readUnchecked(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String slashed(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
