package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Coverage for the adapter surface across every column type: SQL type mapping
/// ([VortexTable#getRowType]), row materialisation ([VortexTable] scan + enumerator),
/// [VortexSchema] lookup, and [VortexAggregates].
class VortexAdapterCoverageTest {

    // One column per logical type the adapter maps; three rows.
    private static final DType.Struct SCHEMA = DType.structBuilder()
            .field("i8", DType.I8)
            .field("i16", DType.I16)
            .field("i32", DType.I32)
            .field("i64", DType.I64)
            .field("u8", DType.U8)
            .field("u16", DType.U16)
            .field("u32", DType.U32)
            .field("u64", DType.U64)
            .field("f32", DType.F32)
            .field("f64", DType.F64)
            .field("s", DType.UTF8)
            .field("b", DType.BOOL)
            .build();

    @TempDir
    static Path tmp;
    private static Path file;

    @BeforeAll
    static void write() throws Exception {
        file = tmp.resolve("alltypes.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            w.writeChunk(Map.ofEntries(
                    Map.entry("i8", new byte[]{1, 2, 3}),
                    Map.entry("i16", new short[]{10, 20, 30}),
                    Map.entry("i32", new int[]{100, 200, 300}),
                    Map.entry("i64", new long[]{1000L, 2000L, 3000L}),
                    Map.entry("u8", new byte[]{4, 5, 6}),
                    Map.entry("u16", new short[]{40, 50, 60}),
                    Map.entry("u32", new int[]{400, 500, 600}),
                    Map.entry("u64", new long[]{4000L, 5000L, 6000L}),
                    Map.entry("f32", new float[]{1.5f, 2.5f, 3.5f}),
                    Map.entry("f64", new double[]{1.25, 2.25, 3.25}),
                    Map.entry("s", new String[]{"a", "b", "c"}),
                    Map.entry("b", new boolean[]{true, false, true})));
        }
    }

    private static ReadRegistry registry() {
        return ReadRegistry.builder().registerServiceLoaded().build();
    }

    @Test
    void getRowType_mapsEveryColumnToItsSqlType() {
        // Given / When
        RelDataType rowType = new VortexTable(file).getRowType(new JavaTypeFactoryImpl());

        // Then — one SqlTypeName per logical type
        Map<String, SqlTypeName> expected = Map.ofEntries(
                Map.entry("i8", SqlTypeName.TINYINT), Map.entry("u8", SqlTypeName.TINYINT),
                Map.entry("i16", SqlTypeName.SMALLINT), Map.entry("u16", SqlTypeName.SMALLINT),
                Map.entry("i32", SqlTypeName.INTEGER), Map.entry("u32", SqlTypeName.INTEGER),
                Map.entry("i64", SqlTypeName.BIGINT), Map.entry("u64", SqlTypeName.BIGINT),
                Map.entry("f32", SqlTypeName.REAL), Map.entry("f64", SqlTypeName.DOUBLE),
                Map.entry("s", SqlTypeName.VARCHAR), Map.entry("b", SqlTypeName.BOOLEAN));
        assertThat(rowType.getFieldList()).hasSize(12);
        expected.forEach((col, sql) ->
                assertThat(rowType.getField(col, false, false).getType().getSqlTypeName()).isEqualTo(sql));
    }

    @Test
    void scan_materialisesEveryColumnToItsJavaType() {
        // Given
        VortexTable table = new VortexTable(file);

        // When — full scan (no filter, all columns)
        List<Object[]> rows = new ArrayList<>();
        Enumerator<Object[]> en = table.scan(null, List.of(), null).enumerator();
        try {
            while (en.moveNext()) {
                rows.add(en.current());
            }
        } finally {
            en.close();
        }

        // Then — first row carries the right boxed types and values
        assertThat(rows).hasSize(3);
        Object[] r0 = rows.getFirst();
        // column order matches the schema
        assertThat(r0[0]).isEqualTo(1);          // i8  -> Integer
        assertThat(r0[2]).isEqualTo(100);        // i32 -> Integer
        assertThat(r0[3]).isEqualTo(1000L);      // i64 -> Long
        assertThat(r0[7]).isEqualTo(4000L);      // u64 -> Long
        assertThat(r0[8]).isEqualTo(1.5);        // f32 -> Double
        assertThat(r0[9]).isEqualTo(1.25);       // f64 -> Double
        assertThat(r0[10]).isEqualTo("a");       // s   -> String
        assertThat(r0[11]).isEqualTo(true);      // b   -> Boolean
    }

    @Test
    void table_totalRowsAndStats() {
        // Given / When / Then
        VortexTable table = new VortexTable(file);
        assertThat(table.totalRows()).isEqualTo(3);
        assertThat(table.statsOf("i64")).isNotNull();
    }

    @Nested
    class Schema {

        @Test
        void table_returnsRegisteredTable() {
            // Given
            VortexSchema schema = new VortexSchema(Map.of("t", file));

            // When / Then
            assertThat(schema.table("t")).isNotNull();
        }

        @Test
        void table_unknownName_throws() {
            // Given
            VortexSchema schema = new VortexSchema(Map.of("t", file));

            // When / Then
            assertThatThrownBy(() -> schema.table("nope"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nope");
        }
    }

    @Nested
    class Aggregates {

        @Test
        void integerColumn_sumIsExactLong() throws Exception {
            // Given / When
            try (VortexReader reader = VortexReader.open(file, registry())) {
                VortexAggregates.Summary s = VortexAggregates.of(reader, "i64");

                // Then — sum stays a Long (not promoted to Double), avg derived, min/max from stats
                assertThat(s.sum()).isInstanceOf(Long.class).isEqualTo(6000L);
                assertThat(s.count()).isEqualTo(3);
                assertThat(s.avg()).isEqualTo(2000.0);
                assertThat(((Number) s.min()).longValue()).isEqualTo(1000L);
                assertThat(((Number) s.max()).longValue()).isEqualTo(3000L);
                assertThat(s.minMaxSource()).isEqualTo(VortexAggregates.Source.ZONE_STATS_PUSHDOWN);
                assertThat(s.sumSource()).isEqualTo(VortexAggregates.Source.FULL_SCAN);
            }
        }

        @Test
        void floatColumn_sumIsDouble() throws Exception {
            // Given / When
            try (VortexReader reader = VortexReader.open(file, registry())) {
                VortexAggregates.Summary s = VortexAggregates.of(reader, "f64");

                // Then
                assertThat(s.sum()).isInstanceOf(Double.class);
                assertThat(s.sum().doubleValue()).isEqualTo(6.75);
                assertThat(s.avg()).isEqualTo(2.25);
            }
        }
    }
}
