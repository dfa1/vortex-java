package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.apache.calcite.linq4j.Enumerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Unsigned high-half handling in the Calcite adapter (#216): U32 widens losslessly into `BIGINT`
/// and U64 maps to `DECIMAL(20, 0)`, which holds its full range (#665). The canonical motivating shape is an unsigned
/// integer column (like uci-wine's magnesium); here each column carries a boundary high-half value.
class UnsignedColumnTest {

    // A U32 value beyond signed INTEGER (0xB2D05E00): getInt reads it as a negative int.
    private static final long U32_HIGH = 3_000_000_000L;
    // A U64 value with the high bit set (2^63): getLong reads it as Long.MIN_VALUE.
    private static final long U64_HIGH = Long.MIN_VALUE;

    private static final DType.Struct SCHEMA = DType.structBuilder()
            .field(ColumnName.of("u32"), DTypes.U32)
            .field(ColumnName.of("u64"), DTypes.U64)
            .build();

    @TempDir
    Path tmp;

    @Test
    void scan_widensU32ToLongAndKeepsInRangeU64() throws Exception {
        // Given — a row whose U32 exceeds signed INTEGER and whose U64 stays in the low half
        Path file = write("in-range.vortex", WriteOptions.defaults(), (int) U32_HIGH, 4000L);

        // When — a full scan materializes the row through VortexTable.value
        List<Object[]> rows = drain(new VortexTable(file));

        // Then — U32 widened to a positive Long (not a negative int), U64 kept as its Long value
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(U32_HIGH);
        assertThat(rows.getFirst()[1]).isEqualTo(new BigDecimal(4000));
    }

    @Test
    void scan_u64HighHalf_surfacesTheUnsignedValue() throws Exception {
        // Given — a U64 value with the high bit set, which no signed BIGINT can hold
        Path file = write("u64-high.vortex", WriteOptions.defaults(), 0, U64_HIGH);

        // When
        List<Object[]> rows = drain(new VortexTable(file));

        // Then — DECIMAL(20, 0) carries the exact unsigned value (#665)
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[1]).isEqualTo(new BigDecimal("9223372036854775808"));
    }

    @Test
    void sum_u32HighHalf_widensExactlyOnFullScan() throws Exception {
        // Given — zone maps off forces the streaming scanSum path (no per-zone SUM to fold)
        Path file = write("sum-u32.vortex", noZoneMaps(), (int) U32_HIGH, 1L);

        // When
        try (VortexReader reader = VortexReader.open(file, registry())) {
            VortexAggregates.Summary result = VortexAggregates.of(reader, ColumnName.of("u32"));

            // Then — the U32 element widens via toUnsignedLong, not sign-extension
            assertThat(result.sumSource()).isEqualTo(VortexAggregates.Source.FULL_SCAN);
            assertThat(result.sum()).isInstanceOf(Long.class).isEqualTo(U32_HIGH);
        }
    }

    @ParameterizedTest(name = "zone maps {0}")
    @ValueSource(booleans = {true, false})
    void summary_u64HighHalf_isExactAndUnsigned(boolean zoneMaps) throws Exception {
        // Given — 2^63 and 2^64 - 1, whose raw bits are the two most negative longs (#665)
        Path file = write("sum-u64.vortex", zoneMaps ? WriteOptions.defaults() : noZoneMaps(), 0, U64_HIGH, -1L);

        // When
        try (VortexReader reader = VortexReader.open(file, registry())) {
            VortexAggregates.Summary result = VortexAggregates.of(reader, ColumnName.of("u64"));

            // Then — compared and summed as unsigned, with no wraparound
            BigInteger half = new BigInteger("9223372036854775808");
            BigInteger max = new BigInteger("18446744073709551615");
            assertThat(result.min()).isEqualTo(half);
            assertThat(result.max()).isEqualTo(max);
            assertThat(result.count()).isEqualTo(2);
            assertThat(result.sum()).isEqualTo(half.add(max));
            assertThat(result.avg()).isEqualTo(half.add(max).doubleValue() / 2);
        }
    }

    private Path write(String name, WriteOptions opts, int u32, long... u64) throws Exception {
        Path file = tmp.resolve(name);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, SCHEMA, opts)) {
            writer.writeChunk(Map.of(
                    ColumnName.of("u32"), u32Column(u32, u64.length),
                    ColumnName.of("u64"), u64));
        }
        return file;
    }

    private static int[] u32Column(int first, int rows) {
        int[] column = new int[rows];
        column[0] = first;
        return column;
    }

    private static WriteOptions noZoneMaps() {
        // Same shape as the adapter coverage test's zone-maps-off options: the second flag disables
        // zone maps so no per-zone SUM exists and VortexAggregates falls back to scanSum.
        return WriteOptions.cascading(0).withZoneMaps(false).withGlobalDict(true).withoutEditions();
    }

    private static ReadRegistry registry() {
        return ReadRegistry.builder().registerDefaults().build();
    }

    private static List<Object[]> drain(VortexTable table) {
        List<Object[]> rows = new ArrayList<>();
        try (Enumerator<Object[]> en = table.scan(null, List.of(), null).enumerator()) {
            while (en.moveNext()) {
                rows.add(en.current());
            }
        }
        return rows;
    }
}
