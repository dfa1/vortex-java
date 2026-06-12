package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.encode.BitpackedEncodingEncoder;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.writer.VortexReads.readAllInts;
import static org.assertj.core.api.Assertions.assertThat;

class BitpackedEncodingTest {

    private static final DType.Struct I32_SCHEMA = new DType.Struct(
            List.of("value"),
            List.of(new DType.Primitive(PType.I32, false)),
            false);

    private static ReadRegistry bitpackedRegistry() {
        return ReadRegistry.builder().register(new BitpackedEncodingDecoder()).build();
    }

    @Test
    void roundTrip_positiveIntegers(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("bp.vtx");
        int[] data = {0, 1, 2, 3, 4, 5, 6, 7};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new BitpackedEncodingEncoder()))) {
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
                     List.of(new BitpackedEncodingEncoder()))) {
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
                     List.of(new BitpackedEncodingEncoder()))) {
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
                     List.of(new BitpackedEncodingEncoder()))) {
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
                     List.of(new BitpackedEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("value", data));
        }

        // Then
        try (var vf = VortexReader.open(file, bitpackedRegistry())) {
            assertThat(readAllInts(vf, "value")).containsExactly(data);
        }
    }
}
