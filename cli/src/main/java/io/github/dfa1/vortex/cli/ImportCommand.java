package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvImporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ImportCommand {

    private ImportCommand() {
    }

    static int run(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: import <file.csv> [out.vortex]");
            return 1;
        }
        Path csvPath = Path.of(args[1]);
        if (!Files.exists(csvPath)) {
            System.err.println("file not found: " + csvPath);
            return 2;
        }
        Path vortexPath = args.length == 3 ? Path.of(args[2]) : deriveOutputPath(csvPath);
        try {
            CsvImporter.importCsv(csvPath, vortexPath);
            System.out.println("written: " + vortexPath);
            return 0;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 3;
        }
    }

    private static Path deriveOutputPath(Path csvPath) {
        String name = csvPath.getFileName().toString();
        if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        return csvPath.resolveSibling(name + ".vortex");
    }
}
