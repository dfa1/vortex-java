package io.github.dfa1.vortex.jdbc.driver;

import io.github.dfa1.vortex.calcite.VortexCalcite;
import io.github.dfa1.vortex.calcite.VortexCalciteOptions;

import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/// JDBC driver that queries Vortex files with SQL, for IDEs and tools that only accept a URL:
///
/// ```
/// jdbc:vortex:/data/vortex
/// jdbc:vortex:/data/vortex?schema=lake&table.trades=/other/trades.vortex
/// ```
///
/// The path is a directory whose `*.vortex` files become tables named after the file (`ohlc.vortex`
/// is `vtx.ohlc`); columns come from each file, never declared. The query string takes `schema`
/// (default `vtx`) and `table.<name>=<path>` (an explicit file, which wins over a discovered one).
/// Any other key is rejected, so a typo cannot silently expose no tables. Values are percent-decoded (a `+` is a plus).
///
/// The URL maps one-to-one onto [VortexCalciteOptions]; the connection is
/// [VortexCalcite#connect(VortexCalciteOptions)], so identifiers are case-sensitive and reserved
/// words such as `close` work unquoted. Driver properties are ignored (IDEs always pass `user` and
/// `password`).
public final class VortexDriver implements Driver {

    static final String PREFIX = "jdbc:vortex:";
    private static final String SCHEMA = "schema";
    private static final String TABLE_PREFIX = "table.";

    static {
        try {
            DriverManager.registerDriver(new VortexDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// Creates the driver; `DriverManager` does this through `META-INF/services/java.sql.Driver`.
    public VortexDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        try {
            return VortexCalcite.connect(options(url));
        } catch (IllegalArgumentException | UncheckedIOException e) {
            // a bad escape, an invalid path, a directory that is not one or cannot be listed
            throw new SQLException(e.getMessage(), "08001", e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /// Parses `url` into the options it describes.
    ///
    /// @param url a `jdbc:vortex:` URL
    /// @return the options
    /// @throws SQLException if the URL has an unknown key, a malformed pair or no files to expose
    static VortexCalciteOptions options(String url) throws SQLException {
        String rest = url.substring(PREFIX.length());
        int query = rest.indexOf('?');
        String path = query < 0 ? rest : rest.substring(0, query);
        VortexCalciteOptions options = VortexCalciteOptions.defaults();
        boolean hasFiles = !path.isEmpty();
        if (hasFiles) {
            options = options.withDirectory(Path.of(decode(path)));
        }
        if (query >= 0) {
            for (String pair : rest.substring(query + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    throw invalid(url, "expected key=value, got '" + pair + "'");
                }
                String key = decode(pair.substring(0, eq));
                String value = decode(pair.substring(eq + 1));
                if (key.equals(SCHEMA)) {
                    if (value.isEmpty()) {
                        throw invalid(url, "schema is empty");
                    }
                    options = options.withSchemaName(value);
                } else if (key.startsWith(TABLE_PREFIX) && key.length() > TABLE_PREFIX.length()) {
                    if (value.isEmpty()) {
                        throw invalid(url, key + " has no file");
                    }
                    options = options.withTable(key.substring(TABLE_PREFIX.length()), Path.of(value));
                    hasFiles = true;
                } else {
                    throw invalid(url, "unknown key '" + key + "'; expected schema or table.<name>");
                }
            }
        }
        if (!hasFiles) {
            throw invalid(url, "give a directory (jdbc:vortex:/path) or at least one table.<name>=<file>");
        }
        return options;
    }

    /// Percent-decodes `value`. A `+` stays a plus: `URLDecoder` follows form encoding and would turn it
    /// into a space, so `/data/c++/x` would name a directory that does not exist.
    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static SQLException invalid(String url, String reason) {
        return new SQLException("invalid Vortex URL '" + url + "': " + reason, "08001");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("the Vortex driver does not use java.util.logging");
    }
}
