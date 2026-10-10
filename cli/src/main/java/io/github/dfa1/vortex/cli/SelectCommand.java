package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class SelectCommand {

    private static final String USAGE = "usage: select <file.vortex> <col1> [col2 ...] [--where \"<expr>\" ...]";

    /// The columns to print and the `--where` conditions (all must hold) parsed from the arguments after the file.
    private record ParsedArgs(List<ColumnName> columns, List<RowFilter> conditions) {
    }

    private SelectCommand() {
    }

    static int run(String[] args) {
        if (args.length < 3) {
            System.err.println(USAGE);
            return ExitStatus.USAGE_ERROR;
        }
        Path path = Path.of(args[1]);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return ExitStatus.FILE_NOT_FOUND;
        }
        ParsedArgs parsed;
        try {
            parsed = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(USAGE);
            System.err.println("error: " + e.getMessage());
            return ExitStatus.USAGE_ERROR;
        }
        ExportOptions options = ExportOptions.defaults().withColumns(parsed.columns());
        try {
            Writer stdout = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            if (parsed.conditions().isEmpty()) {
                CsvExporter.exportCsv(path, stdout, options);
            } else {
                RowFilter filter = parsed.conditions().size() == 1
                        ? parsed.conditions().getFirst()
                        : new RowFilter.And(parsed.conditions());
                // The scan reads the printed columns and the filtered ones: the row check runs on decoded
                // chunks, so a column that is only filtered on must be there even if it is not printed.
                Set<ColumnName> scanned = new LinkedHashSet<>(parsed.columns());
                scanned.addAll(FilterCommand.filterColumns(filter));
                ScanOptions scanOptions = ScanOptions.columns(scanned.toArray(ColumnName[]::new)).withFilter(filter);
                CsvExporter.exportCsvFiltered(path, stdout, options, scanOptions, FilterCommand.toRowPredicate(filter));
            }
            stdout.flush();
            return ExitStatus.OK;
        } catch (IOException | VortexException e) {
            // VortexException is unchecked but surfaces user-facing failures (an unknown column in a
            // projection or a --where); catching it keeps the CLI from dumping a stack trace.
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static ParsedArgs parseArgs(String[] args) {
        List<ColumnName> columns = new ArrayList<>();
        List<RowFilter> conditions = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if ("--where".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for --where");
                }
                conditions.add(FilterCommand.parseFilter(args[++i]));
            } else {
                columns.add(ColumnName.of(args[i]));
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("expected at least one column");
        }
        return new ParsedArgs(columns, conditions);
    }
}
