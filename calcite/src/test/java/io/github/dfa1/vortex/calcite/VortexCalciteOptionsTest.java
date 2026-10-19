package io.github.dfa1.vortex.calcite;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/// Covers [VortexCalciteOptions] and directory discovery through [VortexCalcite#connect(Path)]:
/// a table is named after its file, columns come from the file's own dtype, and an explicit
/// binding wins over a discovered file.
class VortexCalciteOptionsTest {

    @TempDir
    static Path tmp;
    private static Path dir;
    private static Path other;

    @BeforeAll
    static void writeFiles() throws Exception {
        dir = Files.createDirectory(tmp.resolve("data"));
        // Different row counts tell the discovered file from the explicitly bound one.
        OhlcGenerator.write(dir.resolve("ohlc.vortex"), 20_000, 10_000);
        OhlcGenerator.write(dir.resolve("small.vortex"), 100, 100);
        Files.writeString(dir.resolve("notes.txt"), "not a vortex file");
        other = tmp.resolve("other.vortex");
        OhlcGenerator.write(other, 7, 7);
    }

    @Test
    void defaults_useVtxSchemaAndNoTables() {
        // Given / When
        VortexCalciteOptions result = VortexCalciteOptions.defaults();

        // Then
        assertThat(result.schemaName()).isEqualTo("vtx");
        assertThat(result.directory()).isEmpty();
        assertThat(result.tables()).isEmpty();
    }

    @Test
    void withMethods_returnCopiesAndLeaveTheOriginalUntouched() {
        // Given
        VortexCalciteOptions sut = VortexCalciteOptions.defaults();

        // When
        VortexCalciteOptions result = sut.withSchemaName("s").withDirectory(dir).withTable("t", other);

        // Then
        assertThat(sut.schemaName()).isEqualTo("vtx");
        assertThat(sut.directory()).isEmpty();
        assertThat(sut.tables()).isEmpty();
        assertThat(result.schemaName()).isEqualTo("s");
        assertThat(result.directory()).contains(dir);
        assertThat(result.tables()).containsExactly(Map.entry("t", other));
    }

    @Test
    void resolveTables_namesTablesAfterFilesAndIgnoresOtherExtensions() {
        // Given
        VortexCalciteOptions sut = VortexCalciteOptions.defaults().withDirectory(dir);

        // When
        Map<String, Path> result = sut.resolveTables();

        // Then
        assertThat(result).containsOnlyKeys("ohlc", "small");
    }

    @Test
    void resolveTables_explicitTableWinsOverDiscoveredFile() {
        // Given a directory holding ohlc.vortex and an explicit binding for the same name
        VortexCalciteOptions sut = VortexCalciteOptions.defaults().withDirectory(dir).withTable("ohlc", other);

        // When
        Map<String, Path> result = sut.resolveTables();

        // Then
        assertThat(result).containsEntry("ohlc", other).containsKey("small");
    }

    @Test
    void resolveTables_rejectsAPathThatIsNotADirectory() {
        // Given
        VortexCalciteOptions sut = VortexCalciteOptions.defaults().withDirectory(other);

        // When / Then
        assertThatIllegalArgumentException().isThrownBy(sut::resolveTables).withMessageContaining("not a directory");
    }

    @Test
    void resolveTables_skipsHiddenFilesSoOneStrayFileCannotBreakIntrospection() throws Exception {
        // Given a macOS AppleDouble sidecar next to a real table, as an exFAT or SMB volume gets
        Path withSidecar = Files.createDirectory(tmp.resolve("sidecar"));
        OhlcGenerator.write(withSidecar.resolve("ohlc.vortex"), 100, 100);
        Files.writeString(withSidecar.resolve("._ohlc.vortex"), "resource fork, not a Vortex file");

        // When
        Map<String, Path> tables = VortexCalciteOptions.defaults().withDirectory(withSidecar).resolveTables();
        long columns = 0;
        try (Connection conn = VortexCalcite.connect(withSidecar);
             ResultSet rs = conn.getMetaData().getColumns(null, "vtx", "%", "%")) {
            while (rs.next()) {
                columns++;
            }
        }

        // Then the sidecar is no table, and listing every table's columns still works
        assertThat(tables).containsOnlyKeys("ohlc");
        assertThat(columns).isPositive();
    }

    @Test
    void withTables_keepsTheOrderTheEntriesWereAdded() {
        // Given
        Map<String, Path> ordered = new java.util.LinkedHashMap<>();
        ordered.put("z", other);
        ordered.put("a", other);
        ordered.put("m", other);

        // When
        VortexCalciteOptions result = VortexCalciteOptions.defaults().withTables(ordered);

        // Then
        assertThat(result.tables().keySet()).containsExactly("z", "a", "m");
    }

    @Test
    void withMethods_rejectEmptyNames() {
        // Given
        VortexCalciteOptions sut = VortexCalciteOptions.defaults();

        // When / Then
        assertThatIllegalArgumentException().isThrownBy(() -> sut.withSchemaName(""));
        assertThatIllegalArgumentException().isThrownBy(() -> sut.withTable("", other));
    }

    @Test
    void withMethods_rejectNulls() {
        // Given
        VortexCalciteOptions sut = VortexCalciteOptions.defaults();

        // When / Then
        assertThatNullPointerException().isThrownBy(() -> sut.withSchemaName(null));
        assertThatNullPointerException().isThrownBy(() -> sut.withDirectory(null));
        assertThatNullPointerException().isThrownBy(() -> sut.withTable(null, other));
        assertThatNullPointerException().isThrownBy(() -> sut.withTable("t", null));
    }

    @Test
    void connectDirectory_queriesEveryDiscoveredFileWithNoDeclaredColumns() throws Exception {
        // Given a directory connection: no table map, no column list
        try (Connection conn = VortexCalcite.connect(dir);
             Statement st = conn.createStatement()) {

            // When both discovered tables are counted
            long ohlc = count(st, "vtx.ohlc");
            long small = count(st, "vtx.small");

            // Then each is named after its file and holds that file's rows
            assertThat(ohlc).isEqualTo(20_000);
            assertThat(small).isEqualTo(100);
        }
    }

    @Test
    void connectOptions_usesTheConfiguredSchemaNameAndExplicitOverride() throws Exception {
        // Given
        VortexCalciteOptions options = VortexCalciteOptions.defaults()
                .withSchemaName("data").withDirectory(dir).withTable("ohlc", other);

        // When
        try (Connection conn = VortexCalcite.connect(options);
             Statement st = conn.createStatement()) {
            long result = count(st, "data.ohlc");

            // Then the explicit 7-row file shadowed the discovered 20,000-row one
            assertThat(result).isEqualTo(7);
        }
    }

    private static long count(Statement st, String table) throws Exception {
        try (ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
