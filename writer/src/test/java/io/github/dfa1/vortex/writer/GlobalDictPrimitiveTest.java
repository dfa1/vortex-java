package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.writer.VortexReads.readAllDoubles;
import static io.github.dfa1.vortex.writer.VortexReads.readAllLongs;
import static org.assertj.core.api.Assertions.assertThat;

/// Global dictionary encoding for low-cardinality primitive columns — the integer/float
/// counterpart to [GlobalDictUtf8Test]. Exercises the primitive dict-candidate gate
/// (`isDictCandidate`), the unique-key and codes array construction, and the shared dictionary
/// build across chunks, all asserted by exact value round-trips.
class GlobalDictPrimitiveTest {

    private static DType.Struct i64Schema() {
        return new DType.Struct(List.of(ColumnName.of("v")), List.of(DType.I64), false);
    }

    private static long[] writeI64(Path file, long[][] chunks, WriteOptions options) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, i64Schema(), options)) {
            for (long[] chunk : chunks) {
                sut.writeChunk(Map.of(ColumnName.of("v"), chunk));
            }
        }
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            return readAllLongs(vf, "v");
        }
    }

    @Test
    void lowCardinality_i64_acrossChunks_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — 4 distinct longs cycled across 5 chunks: cardinality 4 with 50/50 distribution,
        // well under the < 50%-unique gate, so the global dict path fires.
        long[] dict = {100L, 200L, 300L, 400L};
        int rowsPerChunk = 1_000;
        int chunkCount = 5;
        long[][] chunks = new long[chunkCount][rowsPerChunk];
        long[] expected = new long[rowsPerChunk * chunkCount];
        for (int c = 0; c < chunkCount; c++) {
            for (int i = 0; i < rowsPerChunk; i++) {
                long value = dict[(c + i) % dict.length];
                chunks[c][i] = value;
                expected[c * rowsPerChunk + i] = value;
            }
        }
        Path file = tmp.resolve("low_i64.vortex");

        // When
        long[] result = writeI64(file, chunks, WriteOptions.cascading(3));

        // Then — every value round-trips, and one shared U8-coded dict keeps the file tiny
        assertThat(result).containsExactly(expected);
        assertThat(Files.size(file)).as("global dict for 5k low-card longs").isLessThan(9_000L);
    }

    @Test
    void highCardinality_i64_fallsBackToCascade(@TempDir Path tmp) throws IOException {
        // Given — every value unique, so the cardinality/ratio gate rejects the dict path
        int rows = 2_000;
        long[] data = new long[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = 1_000_000L + i;
        }
        Path file = tmp.resolve("high_i64.vortex");

        // When
        long[] result = writeI64(file, new long[][]{data}, WriteOptions.cascading(3));

        // Then — correctness, not size: the fallback round-trips exactly
        assertThat(result).containsExactly(data);
    }

    @Test
    void singleValue_i64_fallsBackToConstant(@TempDir Path tmp) throws IOException {
        // Given — one distinct value: dict overhead is pointless, so isDictCandidate returns false
        // at the seen.size() == 1 guard and the column routes to vortex.constant instead.
        int rows = 1_500;
        long[] data = new long[rows];
        java.util.Arrays.fill(data, 42L);
        Path file = tmp.resolve("const_i64.vortex");

        // When
        long[] result = writeI64(file, new long[][]{data}, WriteOptions.cascading(3));

        // Then
        assertThat(result).containsExactly(data);
    }

    @Test
    void cardinalityJustOverU8_i64_roundTrips(@TempDir Path tmp) throws IOException {
        // Given — 300 distinct values over 1000 rows: above the 256 U8-code boundary (codes need
        // U16) and still under the 50%-unique gate, exercising the wider code-ptype path.
        int rows = 1_000;
        int cardinality = 300;
        long[] data = new long[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = i % cardinality;
        }
        Path file = tmp.resolve("u16_i64.vortex");

        // When
        long[] result = writeI64(file, new long[][]{data}, WriteOptions.cascading(3));

        // Then
        assertThat(result).containsExactly(data);
    }

    @Test
    void cardinalityExceededAcrossChunks_i64_fallsBackToPerChunk(@TempDir Path tmp) throws IOException {
        // Given — the first chunk is a dict candidate (400 distinct in 1000 rows, < 50% unique), so
        // the column is buffered for a global dict; a second chunk of fresh uniques then pushes the
        // global cardinality past GLOBAL_DICT_MAX_CARDINALITY (2048). The shared-dict build sees the
        // full key set only at flush, so it trips the per-chunk fallback in writeGlobalDictColumn.
        long[] c0 = new long[1_000];
        for (int i = 0; i < c0.length; i++) {
            c0[i] = i % 400;
        }
        long[] c1 = new long[1_700];
        for (int i = 0; i < c1.length; i++) {
            c1[i] = 1_000_000L + i;
        }
        long[] expected = new long[c0.length + c1.length];
        System.arraycopy(c0, 0, expected, 0, c0.length);
        System.arraycopy(c1, 0, expected, c0.length, c1.length);
        Path file = tmp.resolve("card_exceeded_i64.vortex");

        // When — 400 + 1700 = 2100 distinct > 2048, so the dict path bails to per-chunk segments
        long[] result = writeI64(file, new long[][]{c0, c1}, WriteOptions.cascading(3));

        // Then — the fallback round-trips every value exactly
        assertThat(result).containsExactly(expected);
    }

    @Test
    void cardinalityGrowsPastCapMidFile_i64_demotesImmediatelyToChunkedLayout(@TempDir Path tmp) throws IOException {
        // Given — the core ADR 0021 case. The first chunk is a genuine low-cardinality dict candidate
        // (200 distinct / 1000 rows), so the column is admitted and buffered as codes. Later chunks
        // introduce fresh uniques until the distinct set crosses GLOBAL_DICT_MAX_CARDINALITY (2048)
        // partway through the file. The OLD raw-buffering path would have accumulated every raw value
        // for the whole file before catching this at close(); the new path detects the breach at
        // ingest and demotes IMMEDIATELY. We can't assert on memory in a unit test, but the OUTPUT
        // SHAPE proves the demotion happened: a Chunked-of-Flats layout (one Flat per chunk), NOT a
        // single shared Dict layout. A Dict here would mean the whole column was still buffered.
        var schema = new DType.Struct(List.of(ColumnName.of("v")), List.of(DType.I64), false);
        Path file = tmp.resolve("card_grows_i64.vortex");
        int chunkCount = 6;
        int rowsPerChunk = 1_000;
        long[] expected = new long[chunkCount * rowsPerChunk];
        long next = 0;
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            // When — chunk 0 is low-card (200 distinct), later chunks are all-unique so the running
            // distinct count climbs ~1000/chunk and passes 2048 during chunk 2 or 3.
            for (int c = 0; c < chunkCount; c++) {
                long[] data = new long[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    long value = (c == 0) ? (i % 200) : (1_000_000L + next++);
                    data[i] = value;
                    expected[c * rowsPerChunk + i] = value;
                }
                sut.writeChunk(Map.of(ColumnName.of("v"), data));
            }
        }

        // Then — the demoted column is Chunked-of-Flats (one Flat per chunk), never a shared Dict.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            var columnLayout = unwrapZoned(vf.layout().children().getFirst());
            assertThat(columnLayout.isDict()).as("cardinality-demoted column must not be a global dict").isFalse();
            assertThat(columnLayout.isChunked()).as("demoted column is a chunked layout").isTrue();
            assertThat(columnLayout.children())
                    .as("one Flat per written chunk after cardinality demotion")
                    .hasSize(chunkCount)
                    .allSatisfy(child -> assertThat(child.isFlat()).isTrue());

            // And every value round-trips exactly across all chunks despite the mid-file demotion.
            assertThat(readAllLongs(vf, "v")).containsExactly(expected);
        }
    }

    @Test
    void cardinalityGrowsPastCapMidFile_nullableI64_demotesAndRestoresNulls(@TempDir Path tmp) throws IOException {
        // Given — the nullable twin of cardinalityGrowsPastCapMidFile_i64_..., exercising the
        // reconstructChunk / reconstructPrimitiveValues NULLABLE branch that ADR 0021's "Risks to
        // manage" flags. The first chunk is a genuine low-cardinality dict candidate (200 distinct
        // valid values / 1000 rows) WITH ~10% nulls scattered through it, so the column is admitted and
        // buffered as codes+validity. Later all-unique chunks push the distinct set past
        // GLOBAL_DICT_MAX_CARDINALITY (2048) partway through the file, forcing an IMMEDIATE demotion.
        // Demotion must reconstruct each buffered chunk from its short[] codes AND validity bitmap,
        // restoring null placeholders at invalid positions and re-wrapping in NullableData — the exact
        // path with zero coverage until this test. As with the non-nullable case the OUTPUT SHAPE
        // proves demotion (Chunked-of-Flats, not a shared Dict), and every value plus every null must
        // round-trip end-to-end.
        var schema = new DType.Struct(List.of(ColumnName.of("v")),
                List.of(new DType.Primitive(PType.I64, true)), false);
        Path file = tmp.resolve("card_grows_nullable_i64.vortex");
        int chunkCount = 6;
        int rowsPerChunk = 1_000;
        Long[] expected = new Long[chunkCount * rowsPerChunk];
        long next = 0;
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            // When — chunk 0 is low-card (200 distinct valid values) with every 10th row null so the
            // buffered chunks carry a validity bitmap; later chunks are all-unique so the running
            // distinct count climbs ~1000/chunk and passes 2048 during chunk 2 or 3.
            for (int c = 0; c < chunkCount; c++) {
                Long[] data = new Long[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    Long value;
                    if ((c + i) % 10 == 0) {
                        value = null;
                    } else if (c == 0) {
                        value = (long) (i % 200);
                    } else {
                        value = 1_000_000L + next++;
                    }
                    data[i] = value;
                    expected[c * rowsPerChunk + i] = value;
                }
                sut.writeChunk(Map.of(ColumnName.of("v"), data));
            }
        }

        // Then — the demoted column is Chunked-of-Flats (one Flat per chunk), never a shared Dict.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            var columnLayout = unwrapZoned(vf.layout().children().getFirst());
            assertThat(columnLayout.isDict()).as("cardinality-demoted column must not be a global dict").isFalse();
            assertThat(columnLayout.isChunked()).as("demoted column is a chunked layout").isTrue();
            assertThat(columnLayout.children())
                    .as("one Flat per written chunk after cardinality demotion")
                    .hasSize(chunkCount)
                    .allSatisfy(child -> assertThat(child.isFlat()).isTrue());

            // And every value AND every null round-trips exactly, confirming the nullable
            // reconstruction path (validity restore + NullableData re-wrap) end-to-end.
            assertThat(readNullableLongs(vf, "v")).containsExactly(expected);
        }
    }

    @Test
    void frequencyRemap_dominantValueGetsCode0_regardlessOfFirstSeenOrder(@TempDir Path tmp) throws IOException {
        // Given — a strongly skewed F64 column whose DOMINANT value (0.5, ~98% of rows) is NOT the
        // first value seen: the first row is a rare value (7.0) so first-seen ordering would give the
        // rare value code 0. The primitive path's frequency remap (ADR 0021) must still rank the
        // dominant value to code 0 so SparseEncodingEncoder (fill=0) compresses the mostly-zero codes
        // child. If the remap were dropped (silently reusing the incremental first-seen codes), the
        // dominant value would get a nonzero code, sparse would NOT fire, and the file would be far
        // larger. Asserting a small file therefore pins "dominant → code 0" behaviorally.
        var schema = new DType.Struct(List.of(ColumnName.of("rate")), List.of(DType.F64), false);
        Path file = tmp.resolve("freq_remap_f64.vortex");
        double[] rare = {7.0, 8.0, 9.0, 10.0, 11.0};
        int rowsPerChunk = 5_000;
        int chunkCount = 4;
        double[] expected = new double[rowsPerChunk * chunkCount];
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            for (int c = 0; c < chunkCount; c++) {
                double[] data = new double[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    // Row 0 of chunk 0 is a rare value so 0.5 is not first-seen; ~2% rare elsewhere.
                    double value = (c == 0 && i == 0) ? rare[0]
                            : (i % 50 == 0) ? rare[1 + ((c + i) % (rare.length - 1))] : 0.5;
                    data[i] = value;
                    expected[c * rowsPerChunk + i] = value;
                }
                sut.writeChunk(Map.of(ColumnName.of("rate"), data));
            }
        }

        // Then — the dominant value at code 0 lets the codes child compress to sparse: the whole file
        // (dict of ~6 doubles + mostly-zero U8 codes) stays tiny. A broken remap would balloon it.
        assertThat(Files.size(file)).as("dominant value at code 0 enables sparse codes").isLessThan(8_000L);

        // And values round-trip exactly.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(readAllDoubles(vf, "rate")).containsExactly(expected);
        }
    }

    /// Unwraps a column's Zoned/Stats wrapper (the writer wraps every column in one for zone-map
    /// pruning) to reach the encoding layout beneath.
    private static io.github.dfa1.vortex.reader.layout.Layout unwrapZoned(
            io.github.dfa1.vortex.reader.layout.Layout layout) {
        return layout.isZoned() ? layout.children().getFirst() : layout;
    }

    @Test
    void lowCardinality_i32_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — a low-cardinality I32 column drives the global dict build through the int[]
        // unique-array and codes paths (the narrower I8/I16 carriers are NOT round-tripped here:
        // the reader's lazy dict rejects them — see lowCardinality_i16_globalDict_readerRejects).
        var schema = new DType.Struct(List.of(ColumnName.of("v")), List.of(DType.I32), false);
        int[] data = {10, 20, 30, 10, 20, 30, 10, 20};
        Path file = tmp.resolve("i32.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of(ColumnName.of("v"), data));
        }

        // When
        int[] result;
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = io.github.dfa1.vortex.writer.VortexReads.readAllInts(vf, "v");
        }

        // Then
        assertThat(result).containsExactly(data);
    }

    @Test
    void lowCardinality_i16_notDicted_roundTrips(@TempDir Path tmp) throws IOException {
        // Regression: the writer used to admit I16 to the global dict, producing a vortex.dict
        // column the reader cannot decode ("unsupported ptype for lazy dict: I16"). I8/I16 are now
        // excluded from dict candidacy — matching the Rust compressor, which does not dict narrow
        // ints (RustWritesJavaReadsIntegrationTest#jniWriter_javaReader_lowCardinalityI16) — so a
        // low-card I16 column encodes via the cascade and round-trips cleanly.
        var schema = new DType.Struct(List.of(ColumnName.of("v")), List.of(DType.I16), false);
        short[] data = {1, 2, 3, 1, 2, 3, 1, 2};
        Path file = tmp.resolve("i16.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of(ColumnName.of("v"), data));
        }

        // When — I16 now encodes via the cascade (a ShortArray view), not a dict
        short[] result;
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("v"))) {
            var out = new java.util.ArrayList<Short>();
            iter.forEachRemaining(c -> {
                io.github.dfa1.vortex.reader.array.ShortArray arr = c.column("v");
                for (long i = 0; i < arr.length(); i++) {
                    out.add(arr.getShort(i));
                }
            });
            result = new short[out.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = out.get(i);
            }
        }

        // Then — reads back exactly, no lazy-dict error
        assertThat(result).containsExactly(data);
    }

    @Test
    void lowCardinality_f64_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — F64 is admitted to the dict path (unlike F16/F32); a low-card float column must
        // round-trip through the float dict build.
        var schema = new DType.Struct(List.of(ColumnName.of("v")), List.of(DType.F64), false);
        double[] dictVals = {1.5, 2.5, 3.5};
        int rows = 900;
        double[] data = new double[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = dictVals[i % dictVals.length];
        }
        Path file = tmp.resolve("low_f64.vortex");

        // When
        double[] result;
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of(ColumnName.of("v"), data));
        }
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = readAllDoubles(vf, "v");
        }

        // Then
        assertThat(result).containsExactly(data);
    }

    @Test
    void lowCardinality_nullableI64_acrossChunks_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — a NULLABLE low-cardinality I64 column (4 distinct valid values) with ~10% nulls
        // scattered per chunk. The fix admits nullable numeric columns to the global dict: one shared
        // dictionary, per-chunk masked codes. Guards both size (one dict, not N per-chunk dicts) and
        // that null positions round-trip exactly across all chunks.
        var schema = new DType.Struct(List.of(ColumnName.of("v")),
                List.of(new DType.Primitive(PType.I64, true)), false);
        Path file = tmp.resolve("nullable_i64.vortex");
        long[] dict = {100L, 200L, 300L, 400L};
        int rowsPerChunk = 1_000;
        int chunkCount = 5;

        Long[] expected = new Long[rowsPerChunk * chunkCount];
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            // When — every 10th row is null
            for (int c = 0; c < chunkCount; c++) {
                Long[] data = new Long[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    Long value = (c + i) % 10 == 0 ? null : dict[(c + i) % dict.length];
                    data[i] = value;
                    expected[c * rowsPerChunk + i] = value;
                }
                sut.writeChunk(Map.of(ColumnName.of("v"), data));
            }
        }

        // Then — file stays small: one shared dict + N chunks of U8 codes + masks.
        assertThat(Files.size(file)).as("global dict for 5 nullable chunks of 1000 longs").isLessThan(12_000L);

        // And both values AND null positions round-trip exactly across all chunks.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(readNullableLongs(vf, "v")).containsExactly(expected);
        }
    }

    @Test
    void i64_globalDictDisabled_roundTrips(@TempDir Path tmp) throws IOException {
        // Given — low-card column but globalDict() off: must fall back to per-chunk encoding and
        // still round-trip, guarding the opt-out branch.
        long[] data = new long[600];
        for (int i = 0; i < data.length; i++) {
            data[i] = i % 3;
        }
        Path file = tmp.resolve("nogdict_i64.vortex");

        // When
        long[] result = writeI64(file, new long[][]{data}, WriteOptions.cascading(3).withGlobalDict(false));

        // Then
        assertThat(result).containsExactly(data);
    }

    /// Reads a nullable I64 column, mapping invalid rows to `null` so null positions are asserted
    /// alongside values. A nullable dict column decodes to a [MaskedArray] over the long payload.
    private static List<Long> readNullableLongs(VortexReader vf, String col) {
        var collected = new ArrayList<Long>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                Array arr = c.column(col);
                MaskedArray masked = arr instanceof MaskedArray m ? m : null;
                LongArray la = (LongArray) (masked != null ? masked.inner() : arr);
                for (long i = 0; i < la.length(); i++) {
                    collected.add(masked != null && !masked.isValid(i) ? null : la.getLong(i));
                }
            });
        }
        return collected;
    }
}
