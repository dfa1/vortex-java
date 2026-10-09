package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.writer.encode.DictEncodingEncoder;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.writer.VortexReads.readAllInts;
import static org.assertj.core.api.Assertions.assertThat;

class DictEncodingTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(ColumnName.of("category")),
            List.of(DType.I32),
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
            sut.writeChunk(Map.of(ColumnName.of("category"), data));
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
            sut.writeChunk(Map.of(ColumnName.of("category"), data));
        }

        // Then
        try (var vf = VortexReader.open(file, dictRegistry())) {
            assertThat(readAllInts(vf, "category")).containsExactly(data);
        }
    }

    @Test
    void roundTrip_multipleBatches(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dict_multi.vtx");
        int[] chunk1 = {10, 20, 10};
        int[] chunk2 = {30, 10, 20, 30};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(),
                     List.of(new DictEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("category"), chunk1));
            sut.writeChunk(Map.of(ColumnName.of("category"), chunk2));
        }

        // Then — both batches coalesce into one chunk (Rust's repartition, #470), values in order
        try (var vf = VortexReader.open(file, dictRegistry());
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk c = iter.next()) {
                Array a = c.column(ColumnName.of("category"));
                assertThat(a.length()).isEqualTo(7L);
                MemorySegment result = a.materialize(Arena.ofAuto());
                int[] expected = {10, 20, 10, 30, 10, 20, 30};
                for (int i = 0; i < expected.length; i++) {
                    assertThat(result.get(VortexFormat.LE_INT, 4L * i)).as("row %d", i).isEqualTo(expected[i]);
                }
            }

            assertThat(iter.hasNext()).isFalse();
        }
    }
}
