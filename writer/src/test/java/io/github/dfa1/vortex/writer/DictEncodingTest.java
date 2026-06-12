package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.writer.encode.DictEncodingEncoder;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.writer.VortexReads.readAllInts;
import static org.assertj.core.api.Assertions.assertThat;

class DictEncodingTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("category"),
            List.of(new DType.Primitive(PType.I32, false)),
            false);

    private static ReadRegistry dictRegistry() {
        return ReadRegistry.builder()
                .register(new io.github.dfa1.vortex.reader.decode.DictEncodingDecoder())
                .register(new io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder())
                .build();
    }

    @Test
    void roundTrip_lowCardinality_valuesExpandCorrectly(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dict.vtx");
        int[] data = {1, 2, 1, 3, 2, 1};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(),
                     List.of(new DictEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("category", data));
        }

        // Then
        try (var vf = VortexReader.open(file, dictRegistry())) {
            assertThat(readAllInts(vf, "category")).containsExactly(data);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    void roundTrip_singleUniqueValue_u8Codes(@TempDir Path tmp) throws IOException {
        // Given — all same value, dict has 1 entry, codes are U8(0)
        Path file = tmp.resolve("dict_single.vtx");
        int[] data = {42, 42, 42, 42};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(),
                     List.of(new DictEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("category", data));
        }

        // Then
        try (var vf = VortexReader.open(file, dictRegistry())) {
            assertThat(readAllInts(vf, "category")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dict_multi.vtx");
        int[] chunk1 = {10, 20, 10};
        int[] chunk2 = {30, 10, 20, 30};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(),
                     List.of(new DictEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("category", chunk1));
            sut.writeChunk(Map.of("category", chunk2));
        }

        // Then — process each chunk inside try-with-resources; arena released after close()
        try (var vf = VortexReader.open(file, dictRegistry());
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk c1 = iter.next()) {
                Array a1 = c1.columns().get("category");
                assertThat(a1.length()).isEqualTo(3L);
                assertThat(ArraySegments.of(a1).get(LE_INT, 0)).isEqualTo(10);
                assertThat(ArraySegments.of(a1).get(LE_INT, 4)).isEqualTo(20);
                assertThat(ArraySegments.of(a1).get(LE_INT, 8)).isEqualTo(10);
            }

            assertThat(iter.hasNext()).isTrue();
            try (Chunk c2 = iter.next()) {
                Array a2 = c2.columns().get("category");
                assertThat(a2.length()).isEqualTo(4L);
                assertThat(ArraySegments.of(a2).get(LE_INT, 0)).isEqualTo(30);
                assertThat(ArraySegments.of(a2).get(LE_INT, 4)).isEqualTo(10);
                assertThat(ArraySegments.of(a2).get(LE_INT, 8)).isEqualTo(20);
                assertThat(ArraySegments.of(a2).get(LE_INT, 12)).isEqualTo(30);
            }

            assertThat(iter.hasNext()).isFalse();
        }
    }
}
