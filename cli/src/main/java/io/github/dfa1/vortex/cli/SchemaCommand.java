package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.VortexReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class SchemaCommand {

    private SchemaCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: schema <file.vortex>");
            return ExitStatus.USAGE_ERROR;
        }
        Path path = Path.of(args[1]);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return ExitStatus.FILE_NOT_FOUND;
        }
        try (VortexReader reader = VortexReader.open(path, CliHandles.readRegistry())) {
            printSchema(System.out, path, reader.dtype(), reader.layout().rowCount());
            return ExitStatus.OK;
        } catch (IOException | io.github.dfa1.vortex.core.error.VortexException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static void printSchema(PrintStream out, Path path, DType dtype, long rowCount) {
        if (dtype instanceof DType.Struct s) {
            int n = s.fieldNames().size();
            out.printf("%s (%s rows, %d columns)%n%n",
                    path.getFileName(), formatRows(rowCount), n);
            int idxWidth = Integer.toString(n).length();
            List<String> names = s.fieldNames().stream().map(ColumnName::value).toList();
            int nameWidth = names.stream().mapToInt(String::length).max().orElse(0);
            List<DType> types = s.fieldTypes();
            for (int i = 0; i < n; i++) {
                out.printf("  %" + idxWidth + "d  %-" + nameWidth + "s  %s%n",
                        i + 1, names.get(i), VortexInspector.formatDType(types.get(i)));
            }
        } else {
            out.printf("%s (%s rows)%n%n  %s%n",
                    path.getFileName(), formatRows(rowCount), VortexInspector.formatDType(dtype));
        }
    }

    private static String formatRows(long rows) {
        return String.format(Locale.ROOT, "%,d", rows);
    }
}
