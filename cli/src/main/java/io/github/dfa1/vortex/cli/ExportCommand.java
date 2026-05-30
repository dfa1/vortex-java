package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExportCommand {

    private ExportCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: export <file.vortex>");
            return ExitStatus.USAGE_ERROR;
        }
        Path path = Path.of(args[1]);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return ExitStatus.FILE_NOT_FOUND;
        }
        try {
            Writer stdout = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            CsvExporter.exportCsv(path, stdout, ExportOptions.defaults());
            stdout.flush();
            return ExitStatus.OK;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }
}
