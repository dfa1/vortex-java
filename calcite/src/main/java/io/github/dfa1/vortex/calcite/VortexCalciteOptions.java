package io.github.dfa1.vortex.calcite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/// Settings for [VortexCalcite#connect(VortexCalciteOptions)]: which schema name to register and
/// which `.vortex` files become tables.
///
/// Start from [#defaults()] and adjust with the `withXxx` methods, each of which returns a copy:
///
/// ```java
/// VortexCalciteOptions options = VortexCalciteOptions.defaults()
///         .withDirectory(Path.of("/data/vortex"))      // every *.vortex becomes a table
///         .withTable("trades", Path.of("/other/t.vortex"));
/// ```
///
/// A table is named after its file without the `.vortex` extension (`ohlc.vortex` is `ohlc`; hidden
/// files, whose name starts with a dot, are skipped); an
/// explicit [#withTable(String, Path)] entry wins over a discovered file of the same name. Columns
/// are never declared: they come from each file's embedded dtype.
public final class VortexCalciteOptions {

    private static final String DEFAULT_SCHEMA_NAME = "vtx";
    private static final String EXTENSION = ".vortex";

    private final String schemaName;
    private final Path directory;
    private final Map<String, Path> tables;

    private VortexCalciteOptions(String schemaName, Path directory, Map<String, Path> tables) {
        this.schemaName = schemaName;
        this.directory = directory;
        this.tables = tables;
    }

    /// Returns the starting point: schema name `vtx`, no directory, no explicit tables.
    ///
    /// @return the default options
    public static VortexCalciteOptions defaults() {
        return new VortexCalciteOptions(DEFAULT_SCHEMA_NAME, null, Map.of());
    }

    /// Returns a copy registering the tables under `schemaName`.
    ///
    /// @param schemaName the schema name
    /// @return the updated options
    public VortexCalciteOptions withSchemaName(String schemaName) {
        Objects.requireNonNull(schemaName, "schemaName");
        if (schemaName.isEmpty()) {
            throw new IllegalArgumentException("schemaName is empty");
        }
        return new VortexCalciteOptions(schemaName, directory, tables);
    }

    /// Returns a copy that exposes every `*.vortex` file directly inside `directory` as a table.
    ///
    /// The directory is scanned when the schema is created, so a file added later appears only
    /// on the next connection.
    ///
    /// @param directory the directory to scan
    /// @return the updated options
    public VortexCalciteOptions withDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return new VortexCalciteOptions(schemaName, directory, tables);
    }

    /// Returns a copy that also binds the SQL table `name` to `file`.
    ///
    /// @param name the SQL table name
    /// @param file the `.vortex` file backing it
    /// @return the updated options
    public VortexCalciteOptions withTable(String name, Path file) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(file, "file");
        return withTables(Map.of(name, file));
    }

    /// Returns a copy that also binds every entry of `tables`, as [#withTable(String, Path)] would.
    /// Entries keep the order they were added in.
    ///
    /// @param tables map from SQL table name to the `.vortex` file path
    /// @return the updated options
    public VortexCalciteOptions withTables(Map<String, Path> tables) {
        Objects.requireNonNull(tables, "tables");
        Map<String, Path> copy = new LinkedHashMap<>(this.tables);
        tables.forEach((name, file) -> {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(file, "file");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("table name is empty");
            }
            copy.put(name, file);
        });
        return new VortexCalciteOptions(schemaName, directory, Collections.unmodifiableMap(copy));
    }

    /// Returns the schema name the tables are registered under.
    ///
    /// @return the schema name
    public String schemaName() {
        return schemaName;
    }

    /// Returns the directory scanned for `*.vortex` files, if one was set.
    ///
    /// @return the directory
    public Optional<Path> directory() {
        return Optional.ofNullable(directory);
    }

    /// Returns the explicit table bindings, not including files found in the directory.
    ///
    /// @return an unmodifiable map from SQL table name to file
    public Map<String, Path> tables() {
        return tables;
    }

    /// Resolves the full table set: files found in the directory, then the explicit bindings on top.
    ///
    /// @return map from SQL table name to the `.vortex` file path
    /// @throws IllegalArgumentException if the directory is not a directory
    /// @throws UncheckedIOException     if the directory cannot be listed
    Map<String, Path> resolveTables() {
        Map<String, Path> resolved = new LinkedHashMap<>();
        if (directory != null) {
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("not a directory: " + directory);
            }
            try (Stream<Path> entries = Files.list(directory)) {
                entries.filter(Files::isRegularFile)
                        // a dot-prefixed name is not a table: macOS leaves ._x.vortex sidecars on exFAT/SMB
                        // volumes, and a writer may keep its unfinished file hidden. Such a file has no
                        // footer, and one table that cannot be read breaks introspection of all of them
                        .filter(p -> p.getFileName().toString().endsWith(EXTENSION)
                                && !p.getFileName().toString().startsWith("."))
                        .sorted()
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            resolved.put(name.substring(0, name.length() - EXTENSION.length()), p);
                        });
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list " + directory, e);
            }
        }
        resolved.putAll(tables);
        return resolved;
    }
}
