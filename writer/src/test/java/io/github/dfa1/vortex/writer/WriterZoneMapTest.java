package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.Layout;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip coverage for writer-side `vortex.stats` (zone-map) emission.
class WriterZoneMapTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("v"), List.of(new DType.Primitive(PType.I64, false)), false);

    // Three zones of four rows: [0..3], [4..7], [8..11].
    private static Path write(Path tmp, boolean zoneMaps) throws IOException {
        WriteOptions opts = new WriteOptions(4, zoneMaps, 0.90, 0, true, false);
        Path file = tmp.resolve("zoned-" + zoneMaps + ".vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, opts)) {
            for (int z = 0; z < 3; z++) {
                long[] v = new long[4];
                for (int i = 0; i < 4; i++) {
                    v[i] = z * 4L + i;
                }
                sut.writeChunk(Map.of("v", v));
            }
        }
        return file;
    }

    @Test
    void enableZoneMaps_wrapsColumnInZonedLayoutWithMetadata(@TempDir Path tmp) throws IOException {
        // Given a file written with zone maps on
        Path file = write(tmp, true);

        // When
        try (VortexReader reader = VortexReader.open(file)) {
            Layout column = reader.layout().children().get(0);

            // Then the column is a vortex.stats layout: [data, zones], zone_len=4, MAX+MIN+NULL_COUNT
            assertThat(column.isZoned()).isTrue();
            assertThat(column.children()).hasSize(2);
            ByteBuffer meta = column.metadata().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(meta.getInt(meta.position())).isEqualTo(4);           // zone_len
            assertThat(meta.get(meta.position() + 4)).isEqualTo((byte) 0x58); // bits 3(MAX)+4(MIN)+6(NULL_COUNT)
        }
    }

    @Test
    void disableZoneMaps_leavesColumnUnwrapped(@TempDir Path tmp) throws IOException {
        // Given zone maps off
        Path file = write(tmp, false);

        // When / Then the column is the plain chunked data layout
        try (VortexReader reader = VortexReader.open(file)) {
            assertThat(reader.layout().children().get(0).isZoned()).isFalse();
        }
    }

    @Test
    void zoneMaps_dataStillRoundTrips(@TempDir Path tmp) throws IOException {
        // Given a zone-mapped file
        Path file = write(tmp, true);

        // When the data is scanned (the zoned wrapper is transparent for reads)
        long[] all;
        try (VortexReader reader = VortexReader.open(file)) {
            all = VortexReads.readAllLongs(reader, "v");
        }

        // Then every row is intact
        assertThat(all).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    }

    @Test
    void zoneMaps_statsPayloadDecodesPerZoneMinMax(@TempDir Path tmp) throws IOException {
        // Given a zone-mapped file (3 zones of 4 rows)
        Path file = write(tmp, true);

        // When the per-zone stats table is decoded the way the inspector decodes Rust files
        try (VortexReader reader = VortexReader.open(file)) {
            Layout zonesFlat = reader.layout().children().get(0).children().get(1);
            SegmentSpec spec = reader.footer().segmentSpecs().get(zonesFlat.segments().getFirst());
            try (Arena arena = Arena.ofConfined()) {
                StructArray stats = (StructArray) reader.decodeFlatSegment(spec, statsTableDtype(), 3, arena);
                LongArray max = (LongArray) ((MaskedArray) stats.field("max")).inner();
                LongArray min = (LongArray) ((MaskedArray) stats.field("min")).inner();
                LongArray nullCount = (LongArray) ((MaskedArray) stats.field("null_count")).inner();

                // Then min/max per zone match the source data; the column is non-nullable so
                // every zone reports zero nulls
                assertThat(min.getLong(0)).isZero();
                assertThat(max.getLong(0)).isEqualTo(3);
                assertThat(min.getLong(1)).isEqualTo(4);
                assertThat(max.getLong(1)).isEqualTo(7);
                assertThat(min.getLong(2)).isEqualTo(8);
                assertThat(max.getLong(2)).isEqualTo(11);
                assertThat(nullCount.getLong(0)).isZero();
                assertThat(nullCount.getLong(1)).isZero();
                assertThat(nullCount.getLong(2)).isZero();
            }
        }
    }

    @Test
    void zoneMaps_nullableColumn_recordsPerZoneNullCount(@TempDir Path tmp) throws IOException {
        // Given a nullable I64 column across two zones of two rows: zone 0 = [10, null],
        // zone 1 = [null, null]
        DType.Struct schema = new DType.Struct(
                List.of("v"), List.of(new DType.Primitive(PType.I64, true)), false);
        WriteOptions opts = new WriteOptions(2, true, 0.90, 0, true, false);
        Path file = tmp.resolve("nullable.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            sut.writeChunk(Map.of("v", new io.github.dfa1.vortex.writer.encode.NullableData(
                    new long[]{10L, 0L}, new boolean[]{true, false})));
            sut.writeChunk(Map.of("v", new io.github.dfa1.vortex.writer.encode.NullableData(
                    new long[]{0L, 0L}, new boolean[]{false, false})));
        }

        // When the per-zone stats table is decoded
        try (VortexReader reader = VortexReader.open(file)) {
            Layout zonesFlat = reader.layout().children().get(0).children().get(1);
            SegmentSpec spec = reader.footer().segmentSpecs().get(zonesFlat.segments().getFirst());
            try (Arena arena = Arena.ofConfined()) {
                StructArray stats = (StructArray) reader.decodeFlatSegment(spec, statsTableDtype(), 2, arena);
                LongArray nullCount = (LongArray) ((MaskedArray) stats.field("null_count")).inner();

                // Then each zone's null count is recorded (1 and 2)
                assertThat(nullCount.getLong(0)).isEqualTo(1);
                assertThat(nullCount.getLong(1)).isEqualTo(2);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(PType.class)
    void everyPrimitiveType_emitsZonedLayout(PType ptype, @TempDir Path tmp) throws IOException {
        // Given a two-zone file of the given primitive type (globalDict off so the column stays
        // a plain primitive, not a dict). Every fixed-width primitive carries min/max stats, so
        // every one gets a vortex.stats layout — exercising each per-ptype stat-column arm.
        DType.Struct schema = new DType.Struct(
                List.of("v"), List.of(new DType.Primitive(ptype, false)), false);
        WriteOptions opts = new WriteOptions(2, true, 0.90, 0, false, false);
        Path file = tmp.resolve("ptype-" + ptype + ".vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            sut.writeChunk(Map.of("v", sample(ptype, 0)));
            sut.writeChunk(Map.of("v", sample(ptype, 2)));
        }

        // When / Then
        try (VortexReader reader = VortexReader.open(file)) {
            assertThat(reader.layout().children().get(0).isZoned())
                    .as("ptype %s zoned", ptype)
                    .isTrue();
        }
    }

    @Test
    void noChunks_emitsNoZoneMap(@TempDir Path tmp) throws IOException {
        // Given a file closed without any writeChunk: the column has no chunks, so flushZoneMaps
        // skips it (the empty-chunks guard) and emits no zone-map.
        WriteOptions opts = new WriteOptions(4, true, 0.90, 0, false, false);
        Path file = tmp.resolve("empty.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, opts)) {
            // no writeChunk
            assertThat(sut).isNotNull();
        }

        // When / Then
        try (VortexReader reader = VortexReader.open(file)) {
            assertThat(reader.layout().children().get(0).isZoned()).isFalse();
        }
    }

    @Test
    void chunkWithoutStats_emitsNullCountOnlyZoneMap(@TempDir Path tmp) throws IOException {
        // Given a column with one normal chunk and one empty chunk (no min/max stats): MIN/MAX is
        // dropped, but NULL_COUNT is still emitted — the zone-map carries the NULL_COUNT bit only.
        DType.Struct schema = new DType.Struct(
                List.of("v"), List.of(new DType.Primitive(PType.I64, false)), false);
        WriteOptions opts = new WriteOptions(2, true, 0.90, 0, false, false);
        Path file = tmp.resolve("partial.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            sut.writeChunk(Map.of("v", new long[]{1L, 2L}));
            sut.writeChunk(Map.of("v", new long[]{}));
        }

        // When / Then — zoned with the NULL_COUNT-only bitset (bit 6 = 0x40)
        try (VortexReader reader = VortexReader.open(file)) {
            Layout column = reader.layout().children().get(0);
            assertThat(column.isZoned()).isTrue();
            ByteBuffer meta = column.metadata().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(meta.get(meta.position() + 4)).isEqualTo((byte) 0x40);
        }
    }

    @Test
    void utf8Column_emitsStringMinMaxZoneMap(@TempDir Path tmp) throws IOException {
        // Given a Utf8 column across two zones of two rows: zone 0 = ["apple", "banana"],
        // zone 1 = ["cherry", "date"]. vortex.varbin records full string min/max per chunk, so
        // the zone-map carries MAX+MIN+NULL_COUNT (string min/max), not null_count alone.
        DType.Struct schema = new DType.Struct(
                List.of("s"), List.of(new DType.Utf8(false)), false);
        WriteOptions opts = new WriteOptions(2, true, 0.90, 0, false, false);
        Path file = tmp.resolve("utf8.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            sut.writeChunk(Map.of("s", new String[]{"apple", "banana"}));
            sut.writeChunk(Map.of("s", new String[]{"cherry", "date"}));
        }

        // When / Then the column is zoned with MAX+MIN+NULL_COUNT and string min/max decode per zone
        try (VortexReader reader = VortexReader.open(file)) {
            Layout column = reader.layout().children().get(0);
            assertThat(column.isZoned()).isTrue();
            ByteBuffer meta = column.metadata().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(meta.get(meta.position() + 4)).isEqualTo((byte) 0x58); // MAX+MIN+NULL_COUNT

            Layout zonesFlat = column.children().get(1);
            SegmentSpec spec = reader.footer().segmentSpecs().get(zonesFlat.segments().getFirst());
            try (Arena arena = Arena.ofConfined()) {
                StructArray stats =
                        (StructArray) reader.decodeFlatSegment(spec, utf8StatsTableDtype(), 2, arena);
                VarBinArray max = (VarBinArray) ((MaskedArray) stats.field("max")).inner();
                VarBinArray min = (VarBinArray) ((MaskedArray) stats.field("min")).inner();
                assertThat(min.getString(0)).isEqualTo("apple");
                assertThat(max.getString(0)).isEqualTo("banana");
                assertThat(min.getString(1)).isEqualTo("cherry");
                assertThat(max.getString(1)).isEqualTo("date");
            }
        }
    }

    @Test
    void extensionColumn_emitsStorageMinMaxZoneMap(@TempDir Path tmp) throws IOException {
        // Given an extension column over I64 storage written as a raw long[] (no spec auto-route),
        // two zones of two rows: zone 0 = [10, 11], zone 1 = [20, 21]. ExtEncoding propagates the
        // storage primitive's min/max, so the zone-map carries MAX+MIN+NULL_COUNT — same as I64.
        DType ext = new DType.Extension(
                "test.ext", new DType.Primitive(PType.I64, false), null, false);
        DType.Struct schema = new DType.Struct(List.of("t"), List.of(ext), false);
        WriteOptions opts = new WriteOptions(2, true, 0.90, 0, false, false);
        Path file = tmp.resolve("ext.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            sut.writeChunk(Map.of("t", new long[]{10, 11}));
            sut.writeChunk(Map.of("t", new long[]{20, 21}));
        }

        // When / Then the column is zoned with MAX+MIN+NULL_COUNT and the stats decode per zone
        try (VortexReader reader = VortexReader.open(file)) {
            Layout column = reader.layout().children().get(0);
            assertThat(column.isZoned()).isTrue();
            ByteBuffer meta = column.metadata().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(meta.get(meta.position() + 4)).isEqualTo((byte) 0x58); // MAX+MIN+NULL_COUNT

            Layout zonesFlat = column.children().get(1);
            SegmentSpec spec = reader.footer().segmentSpecs().get(zonesFlat.segments().getFirst());
            try (Arena arena = Arena.ofConfined()) {
                StructArray stats = (StructArray) reader.decodeFlatSegment(spec, statsTableDtype(), 2, arena);
                LongArray max = (LongArray) ((MaskedArray) stats.field("max")).inner();
                LongArray min = (LongArray) ((MaskedArray) stats.field("min")).inner();
                assertThat(min.getLong(0)).isEqualTo(10);
                assertThat(max.getLong(0)).isEqualTo(11);
                assertThat(min.getLong(1)).isEqualTo(20);
                assertThat(max.getLong(1)).isEqualTo(21);
            }
        }
    }

    @Test
    void dictColumn_emitsNullCountZoneMapWrappingDict(@TempDir Path tmp) throws IOException {
        // Given a low-cardinality Utf8 column across 2 chunks of 6 with heavy repeats (so the
        // global-dict candidate test, distinct*2 < rows, fires) → vortex.dict; with zone maps the
        // column is vortex.stats wrapping the dict, carrying NULL_COUNT only.
        DType.Struct schema = new DType.Struct(
                List.of("s"), List.of(new DType.Utf8(false)), false);
        WriteOptions opts = new WriteOptions(6, true, 0.90, 0, true, false);
        Path file = tmp.resolve("dict.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            sut.writeChunk(Map.of("s", new String[]{"a", "a", "a", "b", "b", "b"}));
            sut.writeChunk(Map.of("s", new String[]{"c", "c", "c", "a", "a", "a"}));
        }

        // When / Then — zoned over a dict data child, NULL_COUNT-only bitset
        try (VortexReader reader = VortexReader.open(file)) {
            Layout column = reader.layout().children().get(0);
            assertThat(column.isZoned()).isTrue();
            assertThat(column.children().get(0).isDict()).isTrue();
            ByteBuffer meta = column.metadata().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(meta.get(meta.position() + 4)).isEqualTo((byte) 0x40);
        }
    }

    /// Two values starting at `base` in the storage shape for `ptype`.
    private static Object sample(PType ptype, int base) {
        return switch (ptype) {
            case I8, U8 -> new byte[]{(byte) base, (byte) (base + 1)};
            case I16, U16 -> new short[]{(short) base, (short) (base + 1)};
            case F16 -> new short[]{Float.floatToFloat16(base), Float.floatToFloat16(base + 1)};
            case I32, U32 -> new int[]{base, base + 1};
            case I64, U64 -> new long[]{base, base + 1};
            case F32 -> new float[]{base, base + 1};
            case F64 -> new double[]{base, base + 1};
        };
    }

    /// Reconstructs the stats-table dtype the writer emits: MAX, MIN, NULL_COUNT for an I64 column.
    private static DType.Struct statsTableDtype() {
        DType nullableI64 = new DType.Primitive(PType.I64, true);
        return new DType.Struct(
                List.of("max", "max_is_truncated", "min", "min_is_truncated", "null_count"),
                List.of(nullableI64, new DType.Bool(false), nullableI64, new DType.Bool(false),
                        new DType.Primitive(PType.U64, true)),
                false);
    }

    /// Same as [#statsTableDtype()] but with Utf8 (string) MAX/MIN columns.
    private static DType.Struct utf8StatsTableDtype() {
        DType nullableUtf8 = new DType.Utf8(true);
        return new DType.Struct(
                List.of("max", "max_is_truncated", "min", "min_is_truncated", "null_count"),
                List.of(nullableUtf8, new DType.Bool(false), nullableUtf8, new DType.Bool(false),
                        new DType.Primitive(PType.U64, true)),
                false);
    }
}
