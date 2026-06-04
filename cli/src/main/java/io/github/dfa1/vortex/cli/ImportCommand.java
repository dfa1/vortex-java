package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvImporter;
import io.github.dfa1.vortex.csv.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetImporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ImportCommand {

    private ImportCommand() {
    }

    static int run(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: import <file.csv|file.parquet> [out.vortex]");
            return ExitStatus.USAGE_ERROR;
        }
        Path inputPath = Path.of(args[1]);
        if (!Files.exists(inputPath)) {
            System.err.println("file not found: " + inputPath);
            return ExitStatus.FILE_NOT_FOUND;
        }
        Path vortexPath = args.length == 3 ? Path.of(args[2]) : deriveOutputPath(inputPath);
        try {
            String name = inputPath.getFileName().toString();
            if (name.endsWith(".parquet")) {
                return runParquet(inputPath, vortexPath);
            } else {
                return runCsv(inputPath, vortexPath);
            }
        } catch (IOException e) {
            clearProgress();
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static int runCsv(Path csvPath, Path vortexPath) throws IOException {
        ImportOptions options = ImportOptions.defaults()
                .withProgressListener(ImportCommand::renderProgress);
        CsvImporter.importCsv(csvPath, vortexPath, options);
        clearProgress();
        printResult(csvPath, vortexPath, options.writeOptions().allowedCascading());
        return ExitStatus.OK;
    }

    private static int runParquet(Path parquetPath, Path vortexPath) throws IOException {
        io.github.dfa1.vortex.parquet.ImportOptions options =
                io.github.dfa1.vortex.parquet.ImportOptions.defaults()
                        .withProgressListener(ImportCommand::renderProgress);
        ParquetImporter.importParquet(parquetPath, vortexPath, options);
        clearProgress();
        printResult(parquetPath, vortexPath, options.writeOptions().allowedCascading());
        return ExitStatus.OK;
    }

    private static void printResult(Path inputPath, Path vortexPath, int cascadingDepth) throws IOException {
        long inputBytes = Files.size(inputPath);
        long vortexBytes = Files.size(vortexPath);
        double ratio = (double) vortexBytes / inputBytes;
        String sizeChange = ratio <= 1.0
                ? String.format("%.1f%% smaller", (1.0 - ratio) * 100)
                : String.format("%.1f%% larger", (ratio - 1.0) * 100);
        String cascadingInfo = cascadingDepth > 0
                ? String.format(", cascading depth %d", cascadingDepth)
                : "";
        System.out.printf("written: %s  (%s → %s, %s%s)%n",
                vortexPath, formatBytes(inputBytes), formatBytes(vortexBytes),
                sizeChange, cascadingInfo);
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

    private static Path deriveOutputPath(Path inputPath) {
        String name = inputPath.getFileName().toString();
        if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        } else if (name.endsWith(".parquet")) {
            name = name.substring(0, name.length() - 8);
        }
        return inputPath.resolveSibling(name + ".vortex");
    }
}
