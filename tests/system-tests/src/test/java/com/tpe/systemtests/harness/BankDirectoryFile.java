package com.tpe.systemtests.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bank directory the services run with (deploy/config/bank-directory.yml, specs/01-shared-libraries.md §1.4), as
 * the harness hands it to a service: the compose host of csm-simulator replaced by where the harness runs it, so
 * vop-service asks the simulator's responder for a peer bank's payee (17 §3.3).
 */
final class BankDirectoryFile {

    private static final Path SOURCE = Path.of("../../deploy/config/bank-directory.yml");
    private static final String COMPOSE_SIMULATOR = "http://csm-simulator:8090";

    private BankDirectoryFile() {}

    /** A copy under {@code target} with {@code simulator}'s address; the {@code tpe.bank-directory.location} of it. */
    static String withSimulator(URI simulator) {
        try {
            String directory = Files.readString(SOURCE, StandardCharsets.UTF_8);
            if (!directory.contains(COMPOSE_SIMULATOR)) {
                throw new IllegalStateException(SOURCE + " names no " + COMPOSE_SIMULATOR + " to replace");
            }
            String base = simulator.toString().replaceAll("/$", "");
            Path copy = Path.of("target", "systemtest-bank-directory.yml").toAbsolutePath();
            Files.createDirectories(copy.getParent());
            Files.writeString(copy, directory.replace(COMPOSE_SIMULATOR, base), StandardCharsets.UTF_8);
            return "file:" + copy.toString().replace('\\', '/');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
