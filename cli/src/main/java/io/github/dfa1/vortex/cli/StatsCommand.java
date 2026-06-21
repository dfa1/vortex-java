package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.VortexReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class StatsCommand {

    private StatsCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: stats <file.vortex>");
            return ExitStatus.USAGE_ERROR;
        }
        Path path = Path.of(args[1]);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return ExitStatus.FILE_NOT_FOUND;
        }
        try (VortexReader reader = VortexReader.open(path)) {
            if (!(reader.dtype() instanceof DType.Struct schema)) {
                System.err.println("error: root dtype is not a struct");
                return ExitStatus.ERROR;
            }
            long totalRows = reader.layout().rowCount();
            Map<String, ArrayStats> stats = reader.columnStats();

            System.out.printf("rows: %,d%n%n", totalRows);
            System.out.printf("%-20s  %-12s  %15s  %15s%n", "column", "type", "min", "max");
            System.out.println("-".repeat(67));

            List<String> names = schema.fieldNames();
            List<DType> types = schema.fieldTypes();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                String type = formatDType(types.get(i));
                ArrayStats s = stats.getOrDefault(name, ArrayStats.empty());
                String min = s.min() != null ? s.min().toString() : "n/a";
                String max = s.max() != null ? s.max().toString() : "n/a";
                System.out.printf("%-20s  %-12s  %15s  %15s%n", name, type, min, max);
            }
            return ExitStatus.OK;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    private static String formatDType(DType dtype) {
        return switch (dtype) {
            case DType.Primitive p -> p.ptype().name().toLowerCase();
            case DType.Bool _ -> "bool";
            case DType.Utf8 _ -> "utf8";
            case DType.Struct _ -> "struct";
            default -> dtype.getClass().getSimpleName().toLowerCase();
        };
    }
}
