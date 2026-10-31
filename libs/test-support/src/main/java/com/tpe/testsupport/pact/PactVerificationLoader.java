package com.tpe.testsupport.pact;

import au.com.dius.pact.core.model.DirectorySource;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.provider.junitsupport.loader.PactFolderLoader;
import au.com.dius.pact.provider.junitsupport.loader.PactLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The pacts a {@link PactVerification} verifies (specs/20-testing-strategy.md §10.3): this commit's from
 * {@code pact.folder} and the last release's, the newest {@code <major>.<minor>.<patch>} folder under
 * {@code pact.released.dir}. The parent POM sets both for Failsafe. Without a released version, or with
 * {@code pact.released.skip=true}, the backward part has nothing to verify and logs so. Logs through
 * {@code System.Logger}: test-support depends on test libraries only (specs/00-engineering-baseline.md §1).
 */
public final class PactVerificationLoader implements PactLoader {

    static final String COLLECTED_DIR = "pact.folder";
    static final String RELEASED_DIR = "pact.released.dir";
    static final String SKIP_RELEASED = "pact.released.skip";

    private static final System.Logger LOG = System.getLogger(PactVerificationLoader.class.getName());
    /** As the release job names the folders. */
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private final Path collected;
    private final Path release;
    private final String noRelease;

    /** Pact instantiates the loader with its annotation, which carries no settings: they are system properties. */
    PactVerificationLoader(PactVerification annotation) {
        this(Path.of(required(COLLECTED_DIR)), Boolean.getBoolean(SKIP_RELEASED)
                ? Released.skipped()
                : Released.in(Path.of(required(RELEASED_DIR))));
    }

    PactVerificationLoader(Path collected, Released released) {
        this.collected = collected;
        this.release = released.folder().orElse(null);
        this.noRelease = released.why();
    }

    /** The release to verify, or why there is none. */
    record Released(Optional<Path> folder, String why) {

        static Released in(Path releasedDir) {
            return new Released(latest(releasedDir), "no released version in " + releasedDir);
        }

        static Released skipped() {
            return new Released(Optional.empty(), "skipped (" + SKIP_RELEASED + ")");
        }
    }

    @Override
    public List<Pact> load(String providerName) throws IOException {
        List<Pact> pacts = new ArrayList<>(new PactFolderLoader(collected.toFile()).load(providerName));
        if (release == null) {
            LOG.log(Level.INFO, "Backward pact check: " + noRelease + ", nothing to verify");
            return pacts;
        }
        List<Pact> released = new PactFolderLoader(release.toFile()).load(providerName);
        LOG.log(Level.INFO, "Backward pact check: " + released.size() + " pact(s) of release "
                + release.getFileName() + " for " + providerName);
        pacts.addAll(released);
        return pacts;
    }

    @Override
    public DirectorySource getPactSource() {
        return new DirectorySource(collected.toFile());
    }

    @Override
    public String description() {
        return "Pacts(" + collected + (release == null ? "" : ", " + release) + ")";
    }

    /** The newest version folder under {@code released}, by number; empty if there is none. */
    static Optional<Path> latest(Path released) {
        if (!Files.isDirectory(released)) {
            return Optional.empty();
        }
        try (Stream<Path> folders = Files.list(released)) {
            return folders.filter(Files::isDirectory)
                    .filter(folder -> VERSION.matcher(folder.getFileName().toString()).matches())
                    .max(Comparator.comparing(folder -> numbers(folder.getFileName().toString()), Arrays::compare));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int[] numbers(String version) {
        return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property " + property + " is not set: provider verifications "
                    + "run with -Ppact-verify, whose Failsafe configuration sets it (specs/20-testing-strategy.md §10.3)");
        }
        return value;
    }
}
