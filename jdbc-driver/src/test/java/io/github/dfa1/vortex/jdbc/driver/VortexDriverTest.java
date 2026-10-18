package io.github.dfa1.vortex.jdbc.driver;

import io.github.dfa1.vortex.calcite.VortexCalciteOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Covers [VortexDriver]: URL parsing onto [VortexCalciteOptions], rejection of malformed URLs, and
/// that `DriverManager` finds the driver through its service entry and runs real SQL.
class VortexDriverTest {

    @TempDir
    static Path tmp;
    private static Path dir;
    private static Path other;

    @BeforeAll
    static void writeFiles() throws Exception {
        dir = Files.createDirectory(tmp.resolve("data"));
        Fixtures.write(dir.resolve("ohlc.vortex"), 2_000);
        other = tmp.resolve("other.vortex");
        Fixtures.write(other, 7);
    }

    @Test
    void options_directoryOnly() throws Exception {
        // Given
        String url = "jdbc:vortex:" + dir;

        // When
        VortexCalciteOptions result = VortexDriver.options(url);

        // Then
        assertThat(result.directory()).contains(dir);
        assertThat(result.schemaName()).isEqualTo("vtx");
        assertThat(result.tables()).isEmpty();
    }

    @Test
    void options_schemaAndExplicitTables() throws Exception {
        // Given a URL with an encoded space, which an IDE produces for a path with one
        String url = "jdbc:vortex:" + dir + "?schema=lake&table.trades=" + other.toString().replace(" ", "%20");

        // When
        VortexCalciteOptions result = VortexDriver.options(url);

        // Then
        assertThat(result.schemaName()).isEqualTo("lake");
        assertThat(result.tables()).containsExactly(Map.entry("trades", other));
    }

    @Test
    void options_tablesWithoutDirectoryAreEnough() throws Exception {
        // Given
        String url = "jdbc:vortex:?table.t=" + other;

        // When
        VortexCalciteOptions result = VortexDriver.options(url);

        // Then
        assertThat(result.directory()).isEmpty();
        assertThat(result.tables()).containsKey("t");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:vortex:",                          // nothing to expose
            "jdbc:vortex:?schema=lake",              // a schema with no files
            "jdbc:vortex:/d?directry=/x",            // typo: silently ignoring it would expose no tables
            "jdbc:vortex:/d?schema",                 // no '='
            "jdbc:vortex:/d?=v",                     // empty key
            "jdbc:vortex:/d?table.=/x",              // empty table name
    })
    void options_rejectsMalformedUrls(String url) {
        // Given / When / Then
        assertThatThrownBy(() -> VortexDriver.options(url))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("invalid Vortex URL")
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("08001");
    }

    @Test
    void acceptsUrl_onlyOwnScheme() {
        // Given
        VortexDriver sut = new VortexDriver();

        // When / Then
        assertThat(sut.acceptsURL("jdbc:vortex:/x")).isTrue();
        assertThat(sut.acceptsURL("jdbc:postgresql://h/db")).isFalse();
        assertThat(sut.acceptsURL(null)).isFalse();
    }

    @Test
    void connect_returnsNullForAForeignUrl() throws Exception {
        // Given
        VortexDriver sut = new VortexDriver();

        // When
        Connection result = sut.connect("jdbc:postgresql://h/db", new Properties());

        // Then the JDBC contract: another driver gets its turn
        assertThat(result).isNull();
    }

    @Test
    void connect_wrapsAMissingDirectoryAsSqlException() {
        // Given
        VortexDriver sut = new VortexDriver();

        // When / Then
        assertThatThrownBy(() -> sut.connect("jdbc:vortex:" + tmp.resolve("missing"), new Properties()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void driverManager_findsTheDriverAndRunsSql() throws Exception {
        // Given the driver registered only through META-INF/services, as in an IDE
        String url = "jdbc:vortex:" + dir + "?table.small=" + other;

        // When
        Driver driver = DriverManager.getDriver(url);
        long ohlc;
        long small;
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            ohlc = count(st, "vtx.ohlc");
            small = count(st, "vtx.small");
        }

        // Then
        assertThat(driver).isInstanceOf(VortexDriver.class);
        assertThat(ohlc).isEqualTo(2_000);
        assertThat(small).isEqualTo(7);
    }

    @Test
    void driverManager_ideStyleCredentialsAreIgnored() throws Exception {
        // Given IDEs always send user and password properties
        Properties info = new Properties();
        info.setProperty("user", "");
        info.setProperty("password", "");

        // When
        try (Connection conn = DriverManager.getConnection("jdbc:vortex:" + dir, info);
             Statement st = conn.createStatement()) {
            long result = count(st, "vtx.ohlc");

            // Then
            assertThat(result).isEqualTo(2_000);
        }
    }

    @Test
    void reservedWordColumnsWorkUnquoted() throws Exception {
        // Given the connection goes through VortexCalcite, which sets JAVA lex and the Babel parser
        try (Connection conn = DriverManager.getConnection("jdbc:vortex:" + dir);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select close, open from vtx.ohlc")) {

            // When
            long result = 0;
            while (rs.next()) {
                result++;
            }

            // Then
            assertThat(result).isEqualTo(2_000);
        }
    }

    private static long count(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
