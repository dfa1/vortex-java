package io.github.dfa1.vortex.calcite;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import java.nio.file.Path;
import java.util.Map;

/// A Calcite schema exposing one or more Vortex files as SQL tables.
///
/// Each entry maps a SQL table name to the `.vortex` file backing it:
///
/// ```java
/// rootSchema.add("vtx", new VortexSchema(Map.of("ohlc", path)));
/// // SELECT symbol, max(high) FROM vtx.ohlc GROUP BY symbol
/// ```
public final class VortexSchema extends AbstractSchema {

    private final Map<String, Table> tables;

    /// Creates a schema from a map of SQL table name to backing Vortex file.
    ///
    /// @param files map from table name to the `.vortex` file path
    public VortexSchema(Map<String, Path> files) {
        this.tables = files.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> new VortexTable(e.getValue())));
    }

    @Override
    protected Map<String, Table> getTableMap() {
        return tables;
    }

    /// Returns the [VortexTable] registered under `name` — useful for reading push-down
    /// instrumentation such as [VortexTable#chunksScannedLastQuery()].
    ///
    /// @param name the SQL table name
    /// @return the backing table
    /// @throws IllegalArgumentException if no table is registered under `name`
    public VortexTable table(String name) {
        Table t = tables.get(name);
        if (!(t instanceof VortexTable vortexTable)) {
            throw new IllegalArgumentException("no Vortex table named: " + name);
        }
        return vortexTable;
    }
}
