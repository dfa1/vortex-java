package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.model.DType;
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
        try (VortexReader reader = VortexReader.open(path)) {
            printSchema(System.out, path, reader.dtype(), reader.layout().rowCount());
            return ExitStatus.OK;
        } catch (IOException e) {
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
            int nameWidth = s.fieldNames().stream().mapToInt(String::length).max().orElse(0);
            List<String> names = s.fieldNames();
            List<DType> types = s.fieldTypes();
            for (int i = 0; i < n; i++) {
                out.printf("  %" + idxWidth + "d  %-" + nameWidth + "s  %s%n",
                        i + 1, names.get(i), formatDType(types.get(i)));
            }
        } else {
            out.printf("%s (%s rows)%n%n  %s%n",
                    path.getFileName(), formatRows(rowCount), formatDType(dtype));
        }
    }

    private static String formatRows(long rows) {
        return String.format(Locale.ROOT, "%,d", rows);
    }

    // Package-private for direct unit testing of every DType arm (see SchemaCommandTest).
    static String formatDType(DType dtype) {
        return switch (dtype) {
            case DType.Struct s -> {
                var sb = new StringBuilder("struct<");
                for (int i = 0; i < s.fieldNames().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(s.fieldNames().get(i)).append(": ").append(formatDType(s.fieldTypes().get(i)));
                }
                sb.append('>');
                yield sb.toString();
            }
            case DType.Primitive(var pt, var nullable) -> pt.name() + (nullable ? "?" : "");
            case DType.Utf8(var nullable) -> "utf8" + (nullable ? "?" : "");
            case DType.Binary(var nullable) -> "binary" + (nullable ? "?" : "");
            case DType.Bool(var nullable) -> "bool" + (nullable ? "?" : "");
            case DType.Null _ -> "null";
            case DType.Decimal(var p, var s, var nullable) -> "decimal(" + p + "," + s + ")" + (nullable ? "?" : "");
            case DType.List(var elem, var nullable) -> "list<" + formatDType(elem) + ">" + (nullable ? "?" : "");
            case DType.FixedSizeList(var elem, var size, var nullable) ->
                    "list<" + formatDType(elem) + ">[" + size + "]" + (nullable ? "?" : "");
            case DType.Extension(var id, var _, var _, var nullable) ->
                    "ext<" + id + ">" + (nullable ? "?" : "");
            case DType.Variant(var nullable) -> "variant" + (nullable ? "?" : "");
        };
    }
}
