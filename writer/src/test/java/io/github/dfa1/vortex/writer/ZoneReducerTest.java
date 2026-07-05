package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.compute.ZoneReducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip tests for [ZoneReducer] — the whole-zone tier of ADR 0013 §6 aggregate push-down.
/// SUM folds the per-zone `SUM` rows the writer embeds in each chunk's zone-map stats, with no data
/// segment decoded.
class ZoneReducerTest {

    private static final DType.Struct I64_SCHEMA = new DType.Struct(
            List.of(ColumnName.of("id")), List.of(DType.I64), false);

    private static final DType.Struct F64_SCHEMA = new DType.Struct(
            List.of(ColumnName.of("v")), List.of(DType.F64), false);

    private static ReadRegistry registry() {
        return ReadRegistry.builder().registerServiceLoaded().build();
    }

    private static long[] range(long from, long to) {
        long[] arr = new long[(int) (to - from + 1)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = from + i;
        }
        return arr;
    }

    @Test
    void integerSumFoldsZonesToExactLong(@TempDir Path tmp) throws IOException {
        // Given — three I64 chunks (one zone each); column total is 1+..+150 = 11325
        Path file = tmp.resolve("ints.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults())) {
            w.writeChunk(Map.of("id", range(1L, 50L)));
            w.writeChunk(Map.of("id", range(51L, 100L)));
            w.writeChunk(Map.of("id", range(101L, 150L)));
        }

        // When
        try (VortexReader reader = VortexReader.open(file, registry())) {
            Number result = new ZoneReducer(reader).sum("id");

            // Then — exact Long (not a Double widening), no data segment decoded
            assertThat(result).isInstanceOf(Long.class).isEqualTo(11325L);
        }
    }

    @Test
    void floatSumFoldsZonesToDouble(@TempDir Path tmp) throws IOException {
        // Given — a single F64 chunk so the sum stat boxes as Double, not Long
        Path file = tmp.resolve("floats.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = VortexWriter.create(ch, F64_SCHEMA, WriteOptions.defaults())) {
            w.writeChunk(Map.of("v", new double[]{1.5, 2.5, 3.0}));
        }

        // When
        try (VortexReader reader = VortexReader.open(file, registry())) {
            Number result = new ZoneReducer(reader).sum("v");

            // Then
            assertThat(result).isInstanceOf(Double.class).isEqualTo(7.0);
        }
    }

    @Test
    void noZoneMapYieldsNull(@TempDir Path tmp) throws IOException {
        // Given — zone maps disabled, so no per-zone SUM exists to fold
        Path file = tmp.resolve("nostats.vtx");
        WriteOptions noZoneMaps = WriteOptions.defaults().withZoneMaps(false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = VortexWriter.create(ch, I64_SCHEMA, noZoneMaps)) {
            w.writeChunk(Map.of("id", range(1L, 50L)));
        }

        // When
        try (VortexReader reader = VortexReader.open(file, registry())) {
            Number result = new ZoneReducer(reader).sum("id");

            // Then — null signals "not answerable from zones", caller must stream
            assertThat(result).isNull();
        }
    }

    @Test
    void overflowedZoneYieldsNull(@TempDir Path tmp) throws IOException {
        // Given — zone maps ON, but one zone's I64 SUM overflows long (Math.addExact in the writer
        // drops it), so a zone map exists yet a zone carries no usable sum. Distinct from
        // noZoneMapYieldsNull: there the table is absent; here it is present but incomplete.
        Path file = tmp.resolve("overflow.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults())) {
            w.writeChunk(Map.of("id", range(1L, 50L)));                                // sums fine
            w.writeChunk(Map.of("id", new long[]{Long.MAX_VALUE, Long.MAX_VALUE}));    // overflows
        }

        // When
        try (VortexReader reader = VortexReader.open(file, registry())) {
            Number result = new ZoneReducer(reader).sum("id");

            // Then — one unusable zone forces the whole fold to bail; no partial sum returned
            assertThat(result).isNull();
        }
    }
}
