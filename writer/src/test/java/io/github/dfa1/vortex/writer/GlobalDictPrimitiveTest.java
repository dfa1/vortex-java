package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        return new DType.Struct(List.of("v"), List.of(new DType.Primitive(PType.I64, false)), false);
    }

    private static long[] writeI64(Path file, long[][] chunks, WriteOptions options) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, i64Schema(), options)) {
            for (long[] chunk : chunks) {
                sut.writeChunk(Map.of("v", chunk));
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
    void lowCardinality_i32_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — a low-cardinality I32 column drives the global dict build through the int[]
        // unique-array and codes paths (the narrower I8/I16 carriers are NOT round-tripped here:
        // the reader's lazy dict rejects them — see lowCardinality_i16_globalDict_readerRejects).
        var schema = new DType.Struct(List.of("v"), List.of(new DType.Primitive(PType.I32, false)), false);
        int[] data = {10, 20, 30, 10, 20, 30, 10, 20};
        Path file = tmp.resolve("i32.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of("v", data));
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
        var schema = new DType.Struct(List.of("v"), List.of(new DType.Primitive(PType.I16, false)), false);
        short[] data = {1, 2, 3, 1, 2, 3, 1, 2};
        Path file = tmp.resolve("i16.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of("v", data));
        }

        // When — I16 now encodes via the cascade (a ShortArray view), not a dict
        short[] result;
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("v"))) {
            var out = new java.util.ArrayList<Short>();
            iter.forEachRemaining(c -> {
                var arr = (io.github.dfa1.vortex.reader.array.ShortArray) c.columns().get("v");
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
        var schema = new DType.Struct(List.of("v"), List.of(new DType.Primitive(PType.F64, false)), false);
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
            sut.writeChunk(Map.of("v", data));
        }
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = readAllDoubles(vf, "v");
        }

        // Then
        assertThat(result).containsExactly(data);
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
}
