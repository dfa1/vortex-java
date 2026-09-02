package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.inspect.ByteSize;
import io.github.dfa1.vortex.csv.ExportOptions;
import io.github.dfa1.vortex.parquet.ParquetExporter;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@SuppressWarnings("java:S106")
final class ExportCommand {

    private ExportCommand() {
    }

    static int run(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: export <file.vortex|url> [out.csv | out.parquet | -]");
            return ExitStatus.USAGE_ERROR;
        }
        String target = args[1];
        boolean toStdout = args.length == 3 && "-".equals(args[2]);
        boolean remote = target.startsWith("http://") || target.startsWith("https://");
        if (remote) {
            return runRemote(target, args, toStdout);
        }
        Path inputPath = Path.of(target);
        if (!Files.exists(inputPath)) {
            System.err.println("file not found: " + inputPath);
            return ExitStatus.FILE_NOT_FOUND;
        }
        Path outputPath = (args.length == 3 && !toStdout)
                ? Path.of(args[2])
                : deriveOutputPath(inputPath);
        try {
            if (!toStdout && outputPath.getFileName().toString().endsWith(".parquet")) {
                return runParquet(inputPath, outputPath);
            }
            return runCsv(inputPath, outputPath, toStdout);
        } catch (IOException e) {
            ProgressBar.clear();
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    /// Handles an `http(s)://` source: Parquet output only (an explicit `out.parquet` path is
    /// required — CSV export and stdout streaming from a remote source aren't supported yet).
    private static int runRemote(String target, String[] args, boolean toStdout) {
        if (toStdout || args.length != 3 || !args[2].endsWith(".parquet")) {
            System.err.println("usage: export <url> out.parquet  (CSV/stdout export from a URL isn't supported yet)");
            return ExitStatus.USAGE_ERROR;
        }
        Path outputPath = Path.of(args[2]);
        try (VortexHandle handle = CliHandles.openTarget(target)) {
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            io.github.dfa1.vortex.parquet.ExportOptions options =
                    io.github.dfa1.vortex.parquet.ExportOptions.defaults()
                            .withProgressListener(ProgressBar::render);
            ParquetExporter.exportParquet(handle, outputPath, options);
            ProgressBar.clear();
            System.out.printf("written: %s  (%s)%n", outputPath, ByteSize.format(Files.size(outputPath)));
            return ExitStatus.OK;
        } catch (IOException e) {
            ProgressBar.clear();
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static int runCsv(Path inputPath, Path outputPath, boolean toStdout) throws IOException {
        ExportOptions options = ExportOptions.defaults()
                .withProgressListener(ProgressBar::render);
        if (toStdout) {
            Writer stdout = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            CsvExporter.exportCsv(inputPath, stdout, options);
            stdout.flush();
            ProgressBar.clear();
        } else {
            CsvExporter.exportCsv(inputPath, outputPath, options);
            ProgressBar.clear();
            printResult(inputPath, outputPath);
        }
        return ExitStatus.OK;
    }

    private static int runParquet(Path inputPath, Path outputPath) throws IOException {
        io.github.dfa1.vortex.parquet.ExportOptions options =
                io.github.dfa1.vortex.parquet.ExportOptions.defaults()
                        .withProgressListener(ProgressBar::render);
        ParquetExporter.exportParquet(inputPath, outputPath, options);
        ProgressBar.clear();
        printResult(inputPath, outputPath);
        return ExitStatus.OK;
    }

    /// Defaults to `.csv` — a Parquet destination must be named explicitly (`out.parquet`),
    /// matching [ExportCommand#run]'s extension-on-the-output-path dispatch.
    private static Path deriveOutputPath(Path inputPath) {
        String name = inputPath.getFileName().toString();
        if (name.endsWith(".vortex")) {
            name = name.substring(0, name.length() - 7);
        }
        return inputPath.resolveSibling(name + ".csv");
    }

    private static void printResult(Path inputPath, Path outputPath) throws IOException {
        long inputBytes = Files.size(inputPath);
        long outputBytes = Files.size(outputPath);
        System.out.printf("written: %s  (%s → %s)%n",
                outputPath, ByteSize.format(inputBytes), ByteSize.format(outputBytes));
    }
}
