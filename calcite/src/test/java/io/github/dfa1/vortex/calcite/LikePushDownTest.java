package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// A prefix `LIKE 'abc%'` is pushed into the scan as a string range, which prunes zones and spares
/// Calcite compiling the pattern per row. Every query must return the rows of the same query over
/// `s || ''`, an expression the translation leaves to Calcite.
class LikePushDownTest {

    @TempDir
    static Path tmp;
    private static Path file;

    /// Two chunks of strings around the edges of a prefix range: the prefix itself, a string just
    /// past its successor, non-Latin-1 code points, and a code point outside the BMP.
    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("like.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("s"), new DType.Utf8(true))
                .build();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{1, 2, 3, 4, 5, 6},
                    ColumnName.of("s"), new String[]{"abc", "abcd", "abd", "ab", "Abc", null}));
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{7, 8, 9, 10, 11, 12},
                    ColumnName.of("s"), new String[]{"Ωmega", "ÿz", "Ā", "a_c", "a%c", "😀x"}));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // the successor of ÿ (U+00FF) is Ā (U+0100), outside Latin-1: it bounds the range but
            // never appears in SQL, which only takes Latin-1 literals
            "ab%", "abc%", "abd%", "A%", "ÿ%", "zzz%", "%",
            // not a prefix pattern: left to Calcite, same answer
            "a_c%", "%c", "abc"})
    void like_returnsTheRowsCalciteReturns(String pattern) throws Exception {
        // Given / When
        List<Long> result = ids("select id from vtx.t where s like '" + pattern + "' order by id");

        // Then
        assertThat(result).isEqualTo(ids("select id from vtx.t where s || '' like '" + pattern + "' order by id"));
    }

    @Test
    void successor_raisesTheLastCodePointSkippingSurrogatesAndMaxima() {
        // Given / When / Then
        assertThat(RexFilterTranslator.successor("abc")).contains("abd");
        assertThat(RexFilterTranslator.successor("a퟿")).contains("a");
        assertThat(RexFilterTranslator.successor("a" + Character.toString(Character.MAX_CODE_POINT))).contains("b");
        assertThat(RexFilterTranslator.successor(Character.toString(Character.MAX_CODE_POINT))).isEmpty();
    }

    private static List<Long> ids(String sql) throws Exception {
        List<Long> out = new ArrayList<>();
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }
}
