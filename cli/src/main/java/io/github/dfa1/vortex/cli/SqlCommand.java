package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.VortexSqlTui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// `sql FILE.vortex [MORE.vortex...]` - opens the interactive SQL shell.
///
/// Each file is registered as a table named after its filename stem (`ohlc.vortex` becomes table
/// `ohlc`, queried as `vtx.ohlc`). Duplicate table names are rejected. Only local `.vortex` files
/// are supported - Calcite scans them directly, so there is no http(s) handle to round-trip.
@SuppressWarnings("java:S106") // CLI command: stdout/stderr are the intended channels
final class SqlCommand {

    private SqlCommand() {
    }

    static int run(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: sql <file.vortex> [more.vortex...]");
            return ExitStatus.USAGE_ERROR;
        }
        Map<String, Path> tables = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            Path path = Path.of(args[i]);
            if (!Files.exists(path)) {
                System.err.println("file not found: " + path);
                return ExitStatus.FILE_NOT_FOUND;
            }
            String name = tableName(path);
            if (tables.containsKey(name)) {
                System.err.println("duplicate table name '" + name + "' from " + path
                        + " (rename one of the files)");
                return ExitStatus.USAGE_ERROR;
            }
            tables.put(name, path);
        }
        try {
            VortexSqlTui.show(tables);
            return ExitStatus.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + CliHandles.describe(e));
            return ExitStatus.ERROR;
        }
    }

    /// Derives the SQL table name from a file path: the filename with its extension stripped.
    ///
    /// @param path the `.vortex` file path
    /// @return the table name
    static String tableName(Path path) {
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
