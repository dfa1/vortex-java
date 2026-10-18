package io.github.dfa1.vortex.calcite;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/// Builds a [VortexSchema] from a Calcite model, for `jdbc:calcite:model=...` URLs and tools that
/// only take a URL:
///
/// ```json
/// { "version": "1.0", "defaultSchema": "vtx",
///   "schemas": [{ "name": "vtx", "type": "custom",
///     "factory": "io.github.dfa1.vortex.calcite.VortexSchemaFactory",
///     "operand": { "directory": "/data/vortex",
///                  "files": { "trades": "/other/trades.vortex" } } }] }
/// ```
///
/// The schema name is the model's `name`. `operand` takes the same two settings as
/// [VortexCalciteOptions]: `directory` (every `*.vortex` in it becomes a table) and `files` (a
/// table name to path map that wins over a discovered file); any other key is rejected, so a typo does not
/// silently expose no tables.
///
/// A model cannot set connection properties. To query reserved-word columns such as `close`
/// unquoted, add `lex=JAVA;parserFactory=org.apache.calcite.sql.parser.babel.SqlBabelParserImpl#FACTORY`
/// to the URL, or connect through [VortexCalcite], which sets both.
public final class VortexSchemaFactory implements SchemaFactory {

    private static final String DIRECTORY = "directory";
    // Not "tables": Calcite fills that key with the model's own (empty) table list.
    private static final String FILES = "files";
    // Calcite adds these to every custom schema's operand; they are not ours to reject.
    private static final Set<String> CALCITE_KEYS = Set.of("modelUri", "baseDirectory", "tables");

    /// Creates the factory Calcite instantiates by class name.
    public VortexSchemaFactory() {
    }

    @Override
    public Schema create(SchemaPlus parent, String name, Map<String, Object> operand) {
        return new VortexSchema(options(name, operand));
    }

    static VortexCalciteOptions options(String name, Map<String, Object> operand) {
        Set<String> unknown = new LinkedHashSet<>(operand.keySet());
        unknown.removeAll(Set.of(DIRECTORY, FILES));
        unknown.removeAll(CALCITE_KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "unknown VortexSchemaFactory operand " + unknown + "; expected " + DIRECTORY + ", " + FILES);
        }
        VortexCalciteOptions options = VortexCalciteOptions.defaults().withSchemaName(name);
        if (operand.get(DIRECTORY) instanceof String directory) {
            options = options.withDirectory(Path.of(directory));
        } else if (operand.containsKey(DIRECTORY)) {
            throw new IllegalArgumentException("operand '" + DIRECTORY + "' must be a string");
        }
        if (operand.get(FILES) instanceof Map<?, ?> files) {
            for (Map.Entry<?, ?> entry : files.entrySet()) {
                if (!(entry.getKey() instanceof String table) || !(entry.getValue() instanceof String file)) {
                    throw new IllegalArgumentException("operand '" + FILES + "' must map table names to path strings");
                }
                options = options.withTable(table, Path.of(file));
            }
        } else if (operand.containsKey(FILES)) {
            throw new IllegalArgumentException("operand '" + FILES + "' must be an object");
        }
        return options;
    }
}
