package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvImporter;
import io.github.dfa1.vortex.csv.ImportOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ImportCommand {

    private ImportCommand() {
    }

    static int run(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: import <file.csv> [out.vortex]");
            return ExitStatus.USAGE_ERROR;
        }
        Path csvPath = Path.of(args[1]);
        if (!Files.exists(csvPath)) {
            System.err.println("file not found: " + csvPath);
            return ExitStatus.FILE_NOT_FOUND;
        }
        Path vortexPath = args.length == 3 ? Path.of(args[2]) : deriveOutputPath(csvPath);
        ImportOptions options = ImportOptions.defaults()
                .withProgressListener(ImportCommand::renderProgress);
        try {
            CsvImporter.importCsv(csvPath, vortexPath, options);
            clearProgress();
            long csvBytes = Files.size(csvPath);
            long vortexBytes = Files.size(vortexPath);
            double savings = 1.0 - (double) vortexBytes / csvBytes;
            int cascadingDepth = options.writeOptions().allowedCascading();
            String cascadingInfo = cascadingDepth > 0 ? String.format(", cascading depth %d", cascadingDepth) : "";
            System.out.printf("written: %s  (%s → %s, %.1f%% smaller%s)%n",
                    vortexPath, formatBytes(csvBytes), formatBytes(vortexBytes), savings * 100, cascadingInfo);
            return ExitStatus.OK;
        } catch (IOException e) {
            clearProgress();
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static void renderProgress(long done, long total) {
        int pct = total > 0 ? (int) (done * 100L / total) : 100;
        int filled = pct * 30 / 100;
        String bar = "=".repeat(filled) + (filled < 30 ? ">" : "") + " ".repeat(Math.max(0, 29 - filled));
        System.err.printf("\r  [%s] %3d%%  %,d / %,d rows", bar, pct, done, total);
        System.err.flush();
    }

    private static void clearProgress() {
        System.err.printf("\r%-80s\r", "");
        System.err.flush();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static Path deriveOutputPath(Path csvPath) {
        String name = csvPath.getFileName().toString();
        if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        return csvPath.resolveSibling(name + ".vortex");
    }
}
