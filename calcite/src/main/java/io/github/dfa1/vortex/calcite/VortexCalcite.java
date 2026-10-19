package io.github.dfa1.vortex.calcite;

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/// Entry point for querying Vortex files with SQL through Apache Calcite.
///
/// [#connect(String, Map)] opens a Calcite JDBC connection with a [VortexSchema] already
/// registered, folding the `DriverManager` + `unwrap` + `getRootSchema().add(...)` boilerplate into
/// one call:
///
/// ```java
/// try (Connection conn = VortexCalcite.connect("vtx", Map.of("ohlc", path));
///      Statement st = conn.createStatement();
///      ResultSet rs = st.executeQuery("select min(low), max(high), sum(volume) from vtx.ohlc")) {
///     // whole-table aggregates are answered from zone-map statistics, no data segment decoded
/// }
/// ```
///
/// The connection uses Calcite's `JAVA` lexical policy (case-sensitive identifiers, back-tick
/// quoting, unquoted case preserved) together with the **Babel** SQL parser, which accepts most SQL
/// reserved words as identifiers. A column whose name is a reserved word — `close`, `open`, `value`,
/// `year`, … — can therefore be queried **unquoted**: `select close, open from vtx.ohlc`, which
/// columnar files routinely need.
///
/// The exception is the handful of keywords that begin a typed literal — `date`, `time`,
/// `timestamp`, `interval` — which stay reserved even under Babel (`date` opens `DATE '2020-01-01'`).
/// A column with one of those names must still be back-tick quoted: `select \`date\` from vtx.ohlc`.
public final class VortexCalcite {

    // One instance: building a driver loads a resource and sets up Avatica's factory by reflection.
    private static final Driver CALCITE_DRIVER = new Driver();

    private VortexCalcite() {
    }

    /// Opens a Calcite JDBC connection exposing `tables` under a schema named `schemaName`.
    ///
    /// Each map entry binds a SQL table name to the `.vortex` file backing it; the tables are
    /// reachable as `schemaName.tableName`. The returned connection is the caller's to close, which
    /// releases the schema's readers.
    ///
    /// @param schemaName the schema name the tables are registered under
    /// @param tables     map from SQL table name to the `.vortex` file path
    /// @return an open Calcite JDBC connection with the Vortex schema registered
    /// @throws SQLException if the JDBC connection cannot be opened or the schema cannot be registered
    public static Connection connect(String schemaName, Map<String, Path> tables) throws SQLException {
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(tables, "tables");
        return connect(VortexCalciteOptions.defaults().withSchemaName(schemaName).withTables(tables));
    }

    /// Opens a Calcite JDBC connection exposing every `*.vortex` file in `directory` as a table of
    /// the default schema `vtx`, so `ohlc.vortex` is queried as `vtx.ohlc`.
    ///
    /// @param directory the directory to scan for `.vortex` files
    /// @return an open Calcite JDBC connection with the Vortex schema registered
    /// @throws SQLException if the JDBC connection cannot be opened or the schema cannot be registered
    public static Connection connect(Path directory) throws SQLException {
        return connect(VortexCalciteOptions.defaults().withDirectory(directory));
    }

    /// Opens a Calcite JDBC connection exposing the tables `options` describes.
    ///
    /// The returned connection is the caller's to close, which releases the schema's readers. If
    /// schema registration fails the connection is closed before the failure propagates, so no
    /// connection leaks.
    ///
    /// @param options the schema name and the files to expose
    /// @return an open Calcite JDBC connection with the Vortex schema registered
    /// @throws SQLException if the JDBC connection cannot be opened or the schema cannot be registered
    public static Connection connect(VortexCalciteOptions options) throws SQLException {
        Objects.requireNonNull(options, "options");
        Properties info = new Properties();
        // JAVA lex: case-sensitive identifiers, back-tick quoting, unquoted case preserved — so an
        // unquoted `ohlc` matches the registered table name exactly.
        info.setProperty("lex", "JAVA");
        // Babel parser: accept SQL reserved words (date, close, open, value, …) as identifiers, so
        // columnar files whose column names collide with keywords are queryable unquoted.
        info.setProperty("parserFactory", SqlBabelParserImpl.class.getName() + "#FACTORY");
        // Not DriverManager: it only sees drivers already registered, and a jar loaded by its own
        // classloader (an IDE data source, an application server) has not had Calcite's registered.
        Connection connection = CALCITE_DRIVER.connect("jdbc:calcite:", info);
        try {
            SchemaPlus root = connection.unwrap(CalciteConnection.class).getRootSchema();
            root.add(options.schemaName(), new VortexSchema(options));
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }
}
