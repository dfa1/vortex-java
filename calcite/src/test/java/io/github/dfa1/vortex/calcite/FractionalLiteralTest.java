package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// A fractional literal against an integer column (#701): the reader's integer compare truncated the
/// operand to a long, so `v >= 1.5` matched `v = 1`. Whether SQL reaches that path depends on how
/// Calcite types the comparison, so every operator runs with a `DECIMAL` and a `DOUBLE` literal.
class FractionalLiteralTest {

    private static final ColumnName COLUMN = ColumnName.of("v");

    @TempDir
    Path tmp;

    @ParameterizedTest(name = "v {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // v holds 0, 1, 2, 3; 1.5 lies strictly between two of them, so truncation shows up on
            // both sides of the boundary
            ">= 1.5  | 2",
            ">  1.5  | 2",
            "<= 1.5  | 2",
            "<  1.5  | 2",
            "=  1.5  | 0",
            "<> 1.5  | 4",
            ">= 1.5e0 | 2",
            ">  1.5e0 | 2",
            "<= 1.5e0 | 2",
            "<  1.5e0 | 2",
            "=  1.5e0 | 0",
            "<> 1.5e0 | 4",
            ">= -0.5 | 4",
            "<  -0.5 | 0"})
    void count_fractionalLiteral_comparesByValue(String predicate, long expected) throws Exception {
        // Given
        Path file = write();

        // When
        long result;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from vtx.t where v " + predicate)) {
            rs.next();
            result = rs.getLong(1);
        }

        // Then
        assertThat(result).isEqualTo(expected);
    }

    private Path write() throws Exception {
        DType.Struct schema = DType.structBuilder().field(COLUMN, DTypes.I64).build();
        Path file = tmp.resolve("ints.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            w.writeChunk(Map.of(COLUMN, new long[]{0, 1, 2, 3}));
        }
        return file;
    }
}
