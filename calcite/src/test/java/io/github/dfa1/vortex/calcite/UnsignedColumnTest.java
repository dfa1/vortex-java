package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.apache.calcite.linq4j.Enumerator;
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

/// Unsigned high-half handling in the Calcite adapter (#216): U32 widens losslessly into `BIGINT`
/// and U64 — mapped to signed `BIGINT` because no wider SQL integer exists — fails loud rather than
/// surfacing a two's-complement negative to SQL. The canonical motivating shape is an unsigned
/// integer column (like uci-wine's magnesium); here each column carries a boundary high-half value.
class UnsignedColumnTest {

    // A U32 value beyond signed INTEGER (0xB2D05E00): getInt reads it as a negative int.
    private static final long U32_HIGH = 3_000_000_000L;
    // A U64 value with the high bit set (2^63): getLong reads it as Long.MIN_VALUE.
    private static final long U64_HIGH = Long.MIN_VALUE;

    private static final DType.Struct SCHEMA = DType.structBuilder()
            .field("u32", DType.U32)
            .field("u64", DType.U64)
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
        assertThat(rows.getFirst()[1]).isEqualTo(4000L);
    }

    @Test
    void scan_u64HighHalf_failsLoudRatherThanSurfaceNegative() throws Exception {
        // Given — a U64 value with no lossless signed-BIGINT representation
        Path file = write("u64-high.vortex", WriteOptions.defaults(), 0, U64_HIGH);
        VortexTable table = new VortexTable(file);

        // When / Then — materialization throws instead of yielding a negative BIGINT
        assertThatThrownBy(() -> drain(table))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("U64 value")
                .hasMessageContaining("BIGINT");
    }

    @Test
    void sum_u32HighHalf_widensExactlyOnFullScan() throws Exception {
        // Given — zone maps off forces the streaming scanSum path (no per-zone SUM to fold)
        Path file = write("sum-u32.vortex", noZoneMaps(), (int) U32_HIGH, 1L);

        // When
        try (VortexReader reader = VortexReader.open(file, registry())) {
            VortexAggregates.Summary result = VortexAggregates.of(reader, "u32");

            // Then — the U32 element widens via toUnsignedLong, not sign-extension
            assertThat(result.sumSource()).isEqualTo(VortexAggregates.Source.FULL_SCAN);
            assertThat(result.sum()).isInstanceOf(Long.class).isEqualTo(U32_HIGH);
        }
    }

    @Test
    void sum_u64HighHalf_failsLoudOnFullScan() throws Exception {
        // Given — a U64 element the signed accumulator cannot represent, zone maps off
        Path file = write("sum-u64.vortex", noZoneMaps(), 0, U64_HIGH);

        // When / Then — scanSum throws rather than corrupt the sum with a negative addend
        try (VortexReader reader = VortexReader.open(file, registry())) {
            assertThatThrownBy(() -> VortexAggregates.of(reader, "u64"))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("U64 value");
        }
    }

    private Path write(String name, WriteOptions opts, int u32, long u64) throws Exception {
        Path file = tmp.resolve(name);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, SCHEMA, opts)) {
            writer.writeChunk(Map.of(
                    ColumnName.of("u32"), new int[]{u32},
                    ColumnName.of("u64"), new long[]{u64}));
        }
        return file;
    }

    private static WriteOptions noZoneMaps() {
        // Same shape as the adapter coverage test's zone-maps-off options: the second flag disables
        // zone maps so no per-zone SUM exists and VortexAggregates falls back to scanSum.
        return new WriteOptions(65_536, false, 0.90, 0, true, false);
    }

    private static ReadRegistry registry() {
        return ReadRegistry.builder().registerDefaults().build();
    }

    private static List<Object[]> drain(VortexTable table) {
        List<Object[]> rows = new ArrayList<>();
        Enumerator<Object[]> en = table.scan(null, List.of(), null).enumerator();
        try {
            while (en.moveNext()) {
                rows.add(en.current());
            }
        } finally {
            en.close();
        }
        return rows;
    }
}
