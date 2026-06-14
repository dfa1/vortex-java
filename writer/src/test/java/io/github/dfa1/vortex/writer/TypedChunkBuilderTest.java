package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static io.github.dfa1.vortex.writer.VortexReads.readAllDoubles;
import static io.github.dfa1.vortex.writer.VortexReads.readAllLongs;
import static io.github.dfa1.vortex.writer.VortexReads.readAllStrings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Typed `writeChunk(Consumer<Chunk>)` builder (ADR 0009 part 3).
class TypedChunkBuilderTest {

    private static final DType.Struct SCHEMA = DType.structBuilder()
            .field("timestamp", DType.i64())
            .field("symbol", DType.utf8())
            .field("price", DType.f64())
            .build();

    @Test
    void writeChunk_typed_roundTrips(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("chunk.vortex");

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(c -> c
                    .put("timestamp", new long[]{1_700_000_000_000L, 1_700_000_001_000L})
                    .put("symbol", new String[]{"AAPL", "AAPL"})
                    .put("price", new double[]{189.95, 190.10}));
        }

        // Then — file is readable and values round-trip
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(readAllLongs(vf, "timestamp"))
                    .containsExactly(1_700_000_000_000L, 1_700_000_001_000L);
            assertThat(readAllStrings(vf, "symbol")).containsExactly("AAPL", "AAPL");
            assertThat(readAllDoubles(vf, "price")).containsExactly(189.95, 190.10);
        }
        assertThat(Files.size(file)).isPositive();
    }

    @Test
    void put_unknownColumn_throws(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("err.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When / Then
            assertThatThrownBy(() -> sut.writeChunk(c -> c.put("nope", new long[]{1})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown column: nope");
        }
    }

    @Test
    void put_wrongArrayType_throws(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("err.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When / Then — int[] for I64 column
            assertThatThrownBy(() -> sut.writeChunk(c -> c.put("timestamp", new int[]{1, 2})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timestamp")
                    .hasMessageContaining("I64");
        }
    }

    @Test
    void missingColumn_throws_atClose(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("err.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When / Then
            assertThatThrownBy(() -> sut.writeChunk(c -> c
                    .put("timestamp", new long[]{1L})
                    .put("symbol", new String[]{"A"})))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing column: price");
        }
    }

    @Test
    void nullable_i64Column_acceptsBoxedArrayWithNulls(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("nullable.vortex");
        DType.Struct nullableSchema = DType.structBuilder()
                .field("v", DType.i64().asNullable())
                .build();

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, nullableSchema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(c -> c.put("v", new Long[]{1L, null, 3L}));
        }

        // Then — file is well-formed; the masked encoding output is verified by the
        // existing nullable round-trip tests.
        assertThat(Files.size(file)).isPositive();
    }

    @Test
    void nonNullable_i64Column_rejectsBoxedArray(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("err.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When / Then
            assertThatThrownBy(() -> sut.writeChunk(c -> c.put("timestamp", new Long[]{1L, 2L})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-nullable")
                    .hasMessageContaining("timestamp");
        }
    }

    @Test
    void duplicatePut_throws(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("err.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When / Then
            assertThatThrownBy(() -> sut.writeChunk(c -> c
                    .put("timestamp", new long[]{1L})
                    .put("timestamp", new long[]{2L})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate")
                    .hasMessageContaining("timestamp");
        }
    }
}
