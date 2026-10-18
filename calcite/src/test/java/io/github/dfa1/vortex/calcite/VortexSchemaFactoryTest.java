package io.github.dfa1.vortex.calcite;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/// Covers [VortexSchemaFactory]: the model `operand` maps onto [VortexCalciteOptions] with the same
/// rules as the Java API, and a plain `jdbc:calcite:model=inline:` URL, as an IDE would use, works.
class VortexSchemaFactoryTest {

    @TempDir
    static Path tmp;
    private static Path dir;
    private static Path other;

    @BeforeAll
    static void writeFiles() throws Exception {
        dir = Files.createDirectory(tmp.resolve("data"));
        OhlcGenerator.write(dir.resolve("ohlc.vortex"), 20_000, 10_000);
        other = tmp.resolve("other.vortex");
        OhlcGenerator.write(other, 7, 7);
    }

    @Test
    void options_mapsDirectoryFilesAndTheModelSchemaName() {
        // Given
        Map<String, Object> operand = Map.of("directory", dir.toString(), "files", Map.of("extra", other.toString()));

        // When
        VortexCalciteOptions result = VortexSchemaFactory.options("lake", operand);

        // Then
        assertThat(result.schemaName()).isEqualTo("lake");
        assertThat(result.directory()).contains(dir);
        assertThat(result.tables()).containsExactly(Map.entry("extra", other));
    }

    @Test
    void options_rejectsAnUnknownKeyInsteadOfIgnoringATypo() {
        // Given a misspelled key: silently ignoring it would expose no tables at all
        Map<String, Object> operand = Map.of("directry", dir.toString());

        // When / Then
        assertThatIllegalArgumentException().isThrownBy(() -> VortexSchemaFactory.options("vtx", operand))
                .withMessageContaining("directry");
    }

    @Test
    void options_rejectsWrongValueTypes() {
        // Given / When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VortexSchemaFactory.options("vtx", Map.of("directory", 1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VortexSchemaFactory.options("vtx", Map.of("files", "x")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VortexSchemaFactory.options("vtx", Map.of("files", Map.of("t", 1))));
    }

    @Test
    void inlineModelUrl_queriesTheDirectory() throws Exception {
        // Given a URL carrying the whole configuration, as pasted into an IDE data source. A model
        // cannot set connection properties, so lex=JAVA must be in the URL: without it Calcite
        // uppercases the unquoted table name and finds nothing.
        String url = "jdbc:calcite:lex=JAVA;model=inline:{version:'1.0',defaultSchema:'vtx',schemas:[{name:'vtx',"
                + "type:'custom',factory:'" + VortexSchemaFactory.class.getName() + "',"
                + "operand:{directory:'" + dir + "'}}]}";

        // When
        long result;
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from ohlc")) {
            rs.next();
            result = rs.getLong(1);
        }

        // Then
        assertThat(result).isEqualTo(20_000);
    }
}
