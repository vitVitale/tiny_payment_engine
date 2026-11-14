package com.tpe.systemtests.harness;

import com.tpe.invariantchecker.Violation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * INV-14 (specs/20-testing-strategy.md §3, specs/00-engineering-baseline.md §11): no IBAN or name the tests generated
 * appears in a service log at INFO or above. Scans the {@code *-systemtest.log} files {@link ServiceProcess} writes; a
 * line without a structured level (a startup line, a crash) is scanned too. A violation names the file and line, never
 * the value it found.
 */
public final class PersonalDataLogScan {

    private static final Pattern LEVEL = Pattern.compile("\"level\"\\s*:\\s*\"([A-Z]+)\"");
    private static final Set<String> BELOW_INFO = Set.of("DEBUG", "TRACE");

    private PersonalDataLogScan() {}

    /** The lines of the service logs in {@code directory} that carry one of {@code values}. */
    public static List<Violation> violations(Path directory, Collection<String> values) {
        var violations = new ArrayList<Violation>();
        if (!Files.isDirectory(directory)) {
            return violations;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path log : files.filter(file -> file.getFileName().toString().endsWith("-systemtest.log"))
                    .sorted().toList()) {
                List<String> lines = Files.readAllLines(log);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    var level = LEVEL.matcher(line);
                    if (level.find() && BELOW_INFO.contains(level.group(1))) {
                        continue;
                    }
                    long found = values.stream().filter(line::contains).count();
                    if (found > 0) {
                        violations.add(new Violation("INV-14", log.getFileName() + ":" + (i + 1),
                                found + " generated IBAN(s) or name(s) at " + (level.find(0) ? level.group(1)
                                        : "no level")));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return violations;
    }
}
