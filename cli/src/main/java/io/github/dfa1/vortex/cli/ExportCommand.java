package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.inspect.ByteSize;
import io.github.dfa1.vortex.csv.ExportOptions;

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
            System.err.println("usage: export <file.vortex> [out.csv | -]");
            return ExitStatus.USAGE_ERROR;
        }
        Path inputPath = Path.of(args[1]);
        if (!Files.exists(inputPath)) {
            System.err.println("file not found: " + inputPath);
            return ExitStatus.FILE_NOT_FOUND;
        }
        boolean toStdout = args.length == 3 && "-".equals(args[2]);
        Path outputPath = (args.length == 3 && !toStdout)
                ? Path.of(args[2])
                : deriveOutputPath(inputPath);
        try {
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
        } catch (IOException e) {
            ProgressBar.clear();
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

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
