package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
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

/// `I16` and `U8` map to `SMALLINT` and `I8` to `TINYINT`, whose values Calcite's generated code
/// casts to `Short` and `Byte`. The scan handed it an `Integer` for all three, so any `WHERE` on such
/// a column failed with a ClassCastException.
class NarrowIntegerColumnTest {

    @TempDir
    static Path tmp;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // -300 needs 16 bits; a value read as an Integer failed the SMALLINT cast
            "i16 < 5          | 2",
            // 200 is past the signed byte range: U8 must zero-extend, not read as -56
            "u8 > 100         | 1",
            "i8 < 0           | 1",
            "i16 = 5 and i8 = 9 and u8 = 3 | 1"})
    void whereOnNarrowIntegerColumn_selectsTheMatchingRows(String where, long expected) throws Exception {
        // Given
        Path file = tmp.resolve("narrow.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("i16"), DType.I16)
                .field(ColumnName.of("u8"), DType.U8)
                .field(ColumnName.of("i8"), DType.I8)
                .build();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    ColumnName.of("i16"), new short[]{-300, 5, 2},
                    ColumnName.of("u8"), new byte[]{(byte) 200, 3, 7},
                    ColumnName.of("i8"), new byte[]{-5, 9, 1}));
        }

        // When
        long result;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from vtx.t where " + where)) {
            rs.next();
            result = rs.getLong(1);
        }

        // Then
        assertThat(result).isEqualTo(expected);
    }
}
