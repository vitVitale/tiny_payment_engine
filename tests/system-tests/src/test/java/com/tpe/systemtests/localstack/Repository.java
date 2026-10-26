package com.tpe.systemtests.localstack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Files of this repository, read from the module directory the tests run in. */
final class Repository {

    private static final Path ROOT = Path.of("../..");

    private Repository() {}

    static Path path(String relative) {
        return ROOT.resolve(relative);
    }

    static String read(String relative) throws IOException {
        return Files.readString(path(relative));
    }
}
