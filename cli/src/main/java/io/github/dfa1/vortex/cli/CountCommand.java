package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.io.VortexReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class CountCommand {

    private CountCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: count <file.vortex>");
            return 1;
        }
        Path path = Path.of(args[1]);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return 2;
        }
        try (VortexReader reader = VortexReader.open(path)) {
            System.out.println(reader.layout().rowCount());
            return 0;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 3;
        }
    }
}
