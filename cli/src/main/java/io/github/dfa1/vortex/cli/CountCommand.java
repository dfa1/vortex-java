package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.reader.VortexReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class CountCommand {

    private CountCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: count <file.vortex>");
            return ExitStatus.USAGE_ERROR;
        }
        Path path = Path.of(args[1]);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return ExitStatus.FILE_NOT_FOUND;
        }
        try (VortexReader reader = VortexReader.open(path)) {
            System.out.println(reader.layout().rowCount());
            return ExitStatus.OK;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }
}
