package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvImporter;
import io.github.dfa1.vortex.inspect.ByteSize;
import io.github.dfa1.vortex.csv.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetImporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S106")
final class ImportCommand {

    private record ParsedArgs(Path inputPath, Path outputPath, Character delimiter) {
    }

    private ImportCommand() {
    }

    static int run(String[] args) {
        ParsedArgs parsedArgs;
        try {
            parsedArgs = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: import [--delimiter <char>] <file.csv|file.parquet> [out.vortex]");
            return ExitStatus.USAGE_ERROR;
        }
        Path inputPath = parsedArgs.inputPath();
        if (!Files.exists(inputPath)) {
            System.err.println("file not found: " + inputPath);
            return ExitStatus.FILE_NOT_FOUND;
        }
        try {
            String name = inputPath.getFileName().toString();
            if (name.endsWith(".parquet")) {
                return runParquet(inputPath, parsedArgs.outputPath());
            } else {
                return runCsv(inputPath, parsedArgs.outputPath(), parsedArgs.delimiter());
            }
        } catch (IOException e) {
            clearProgress();
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static ParsedArgs parseArgs(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("missing import arguments");
        }
        List<String> positional = new ArrayList<>();
        Character delimiter = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--delimiter".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for --delimiter");
                }
                String value = args[++i];
                if (value.length() != 1) {
                    throw new IllegalArgumentException("--delimiter must be exactly one character");
                }
                delimiter = value.charAt(0);
                continue;
            }
            positional.add(arg);
        }
        if (positional.size() < 1 || positional.size() > 2) {
            throw new IllegalArgumentException("expected input path and optional output path");
        }
        Path inputPath = Path.of(positional.getFirst());
        Path outputPath = positional.size() == 2 ? Path.of(positional.get(1)) : deriveOutputPath(inputPath);
        return new ParsedArgs(inputPath, outputPath, delimiter);
    }

    private static int runCsv(Path csvPath, Path vortexPath, Character delimiter) throws IOException {
        ImportOptions options = ImportOptions.defaults()
                                        .withProgressListener(ImportCommand::renderProgress);
        if (delimiter != null) {
            options = options.withDelimiter(delimiter);
        }
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
                vortexPath, ByteSize.format(inputBytes), ByteSize.format(vortexBytes),
                sizeChange, cascadingInfo);
    }

    private static void renderProgress(long done, long total) {
        if (total < 0) {
            System.err.printf("\r  imported %,d rows", done);
        } else {
            int pct = total > 0 ? (int) (done * 100L / total) : 100;
            int filled = pct * 30 / 100;
            String bar = "=".repeat(filled) + (filled < 30 ? ">" : "") + " ".repeat(Math.max(0, 29 - filled));
            System.err.printf("\r  [%s] %3d%%  %,d / %,d rows", bar, pct, done, total);
        }
        System.err.flush();
    }

    private static void clearProgress() {
        System.err.printf("\r%-80s\r", "");
        System.err.flush();
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
