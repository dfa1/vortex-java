package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.encoding.BitpackedEncoding;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BitpackedEncodingTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final DType.Struct I32_SCHEMA = new DType.Struct(
            List.of("value"),
            List.of(new DType.Primitive(PType.I32, false)),
            false);

    /// Materializes every chunk of the named I32 column into a primitive int[] by
    /// copying values out of the per-chunk arena before each [io.github.dfa1.vortex.scan.Chunk]
    /// closes. Returns a heap array independent of the scan lifecycle.
    private static int[] readAllInts(VortexReader vf, String col) {
        var collected = new ArrayList<Integer>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                Array arr = c.column(col);
                for (long i = 0; i < arr.length(); i++) {
                    collected.add(ArraySegments.of(arr).get(LE_INT, i * Integer.BYTES));
                }
            });
        }
        return collected.stream().mapToInt(Integer::intValue).toArray();
    }

    private static EncodingRegistry bitpackedRegistry() {
        return EncodingRegistry.builder().register(new BitpackedEncoding()).build();
    }

    @Test
    void roundTrip_positiveIntegers(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("bp.vtx");
        int[] data = {0, 1, 2, 3, 4, 5, 6, 7};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new BitpackedEncoding()))) {
            // When
            sut.writeChunk(Map.of("value", data));
        }

        // Then
        try (var vf = VortexReader.open(file, bitpackedRegistry())) {
            assertThat(readAllInts(vf, "value")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_allSameValue(@TempDir Path tmp) throws IOException {
        // Given — all identical values
        Path file = tmp.resolve("bp_same.vtx");
        int[] data = {42, 42, 42, 42};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new BitpackedEncoding()))) {
            // When
            sut.writeChunk(Map.of("value", data));
        }

        // Then
        try (var vf = VortexReader.open(file, bitpackedRegistry())) {
            assertThat(readAllInts(vf, "value")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_negativeIntegers(@TempDir Path tmp) throws IOException {
        // Given — frame-of-reference shifts negative values
        Path file = tmp.resolve("bp_neg.vtx");
        int[] data = {-10, -5, 0, 5, 10};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new BitpackedEncoding()))) {
            // When
            sut.writeChunk(Map.of("value", data));
        }

        // Then
        try (var vf = VortexReader.open(file, bitpackedRegistry())) {
            assertThat(readAllInts(vf, "value")).containsExactly(data);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    void roundTrip_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("bp_multi.vtx");
        int[] chunk1 = {100, 200, 150};
        int[] chunk2 = {300, 400, 350};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new BitpackedEncoding()))) {
            // When
            sut.writeChunk(Map.of("value", chunk1));
            sut.writeChunk(Map.of("value", chunk2));
        }

        // Then — read both chunks, each in its own try-with-resources scope
        try (var vf = VortexReader.open(file, bitpackedRegistry())) {
            assertThat(readAllInts(vf, "value")).containsExactly(100, 200, 150, 300, 400, 350);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 7, 8, 15, 16, 31})
    void roundTrip_bitWidths(int maxVal, @TempDir Path tmp) throws IOException {
        // Given — verify correct round-trip for various bit widths
        Path file = tmp.resolve("bp_bw_" + maxVal + ".vtx");
        int[] data = new int[maxVal + 1];
        for (int i = 0; i <= maxVal; i++) {
            data[i] = i;
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new BitpackedEncoding()))) {
            // When
            sut.writeChunk(Map.of("value", data));
        }

        // Then
        try (var vf = VortexReader.open(file, bitpackedRegistry())) {
            assertThat(readAllInts(vf, "value")).containsExactly(data);
        }
    }
}
