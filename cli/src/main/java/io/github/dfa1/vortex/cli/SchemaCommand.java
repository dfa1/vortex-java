package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.io.VortexReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            System.out.println(formatDType(reader.dtype()));
            return ExitStatus.OK;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static String formatDType(DType dtype) {
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
            case DType.Null ignored -> "null";
            case DType.Decimal(var p, var s, var nullable) -> "decimal(" + p + "," + s + ")" + (nullable ? "?" : "");
            case DType.List(var elem, var nullable) -> "list<" + formatDType(elem) + ">" + (nullable ? "?" : "");
            case DType.FixedSizeList(var elem, var size, var nullable) ->
                    "list<" + formatDType(elem) + ">[" + size + "]" + (nullable ? "?" : "");
            case DType.Extension(var id, var storage, var meta, var nullable) ->
                    "ext<" + id + ">" + (nullable ? "?" : "");
        };
    }
}
