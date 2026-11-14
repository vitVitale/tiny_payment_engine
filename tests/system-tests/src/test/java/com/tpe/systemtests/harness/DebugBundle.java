package com.tpe.systemtests.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * The debug bundle of a failed system test (specs/20-testing-strategy.md §2): what its {@link Subject} collects about
 * the test's own transactions, written to {@code target/debug-bundles/<class>.<method>.txt}. A watcher runs after the
 * {@code @AfterEach} methods, so a failed invariant check gets its bundle too. Collecting must not hide the failure: a
 * bundle that cannot be made says why in place of its content.
 */
public final class DebugBundle implements TestWatcher {

    static final Path DIRECTORY = Path.of("target", "debug-bundles");

    /** A system test that can tell what happened to its transactions. */
    public interface Subject {

        /** Plain text: payment transitions, ledger entries, the records of the test's payments. */
        String debugBundle() throws Exception;
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        Optional<Object> test = context.getTestInstance();
        if (test.isEmpty() || !(test.get() instanceof Subject subject)) {
            return;
        }
        String content;
        try {
            content = subject.debugBundle();
        } catch (Exception e) {
            content = "no debug bundle: " + e;
        }
        Path file = write(DIRECTORY, context.getRequiredTestClass().getSimpleName() + "."
                + context.getRequiredTestMethod().getName(), cause, content);
        System.err.println("debug bundle of " + context.getDisplayName() + ": " + file.toAbsolutePath());
    }

    static Path write(Path directory, String name, Throwable cause, String content) {
        try {
            Files.createDirectories(directory);
            return Files.writeString(directory.resolve(name + ".txt"), "failure: " + cause + "\n\n" + content,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
