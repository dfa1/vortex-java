package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder;
import io.github.dfa1.vortex.reader.ReadRegistry;
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

import static io.github.dfa1.vortex.writer.VortexReads.readAllLongs;
import static org.assertj.core.api.Assertions.assertThat;

class DeltaEncodingTest {

    private static final DType.Struct I64_SCHEMA = new DType.Struct(
            List.of("ts"),
            List.of(new DType.Primitive(PType.I64, false)),
            false);

    private static ReadRegistry deltaRegistry() {
        return ReadRegistry.builder()
                .register(new io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder())
                .register(new io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder())
                .build();
    }

    @Test
    void roundTrip_monotonicIncreasing(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("delta_inc.vtx");
        long[] data = {100L, 105L, 110L, 115L, 120L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_monotonicDecreasing(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("delta_dec.vtx");
        long[] data = {1000L, 990L, 975L, 950L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_allSameValue(@TempDir Path tmp) throws IOException {
        // Given — deltas all zero, bit_width=0
        Path file = tmp.resolve("delta_same.vtx");
        long[] data = {42L, 42L, 42L, 42L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_singleElement(@TempDir Path tmp) throws IOException {
        // Given — no deltas to store
        Path file = tmp.resolve("delta_single.vtx");
        long[] data = {99L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_mixedDeltas(@TempDir Path tmp) throws IOException {
        // Given — non-monotonic; some deltas negative
        Path file = tmp.resolve("delta_mixed.vtx");
        long[] data = {0L, 5L, 7L, 12L, 11L, 15L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    void roundTrip_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("delta_multi.vtx");
        long[] chunk1 = {1000L, 1001L, 1002L};
        long[] chunk2 = {2000L, 2005L, 2010L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", chunk1));
            sut.writeChunk(Map.of("ts", chunk2));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts"))
                    .containsExactly(1000L, 1001L, 1002L, 2000L, 2005L, 2010L);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 10, 100, 1000})
    void roundTrip_sequentialTimestamps(int n, @TempDir Path tmp) throws IOException {
        // Given — simulate monotonic timestamp column
        Path file = tmp.resolve("delta_seq_" + n + ".vtx");
        long[] data = new long[n];
        for (int i = 0; i < n; i++) {
            data[i] = 1_700_000_000L + i * 1000L;
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        try (var vf = VortexReader.open(file, deltaRegistry())) {
            assertThat(readAllLongs(vf, "ts")).containsExactly(data);
        }
    }
}
