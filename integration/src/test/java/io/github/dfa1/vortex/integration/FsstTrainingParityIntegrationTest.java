package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.writer.ColumnEncoding;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// vortex-java trains the FSST symbol table as Rust's `fsst-rs` 0.7 does (#583): the same sample, the
/// same five generations, the same candidates, gains and cut, and the same order between candidates of
/// equal gain and length. The symbols, their lengths and the code stream are byte for byte Rust's.
class FsstTrainingParityIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                shape("urls", 5000, i -> "https://example.com/products/" + Long.toHexString(mix(i)) + "/detail?ref=" + i % 97),
                shape("emails", 5000, i -> "user." + Long.toHexString(mix(i)) + "@mail-" + i % 17 + ".example.com"),
                shape("urls small", 400, i -> "http://www.example.net/a/" + Long.toHexString(mix(i)) + "/index.html"),
                // under 16 KiB: taken whole, and the last generation pruned
                shape("emails whole", 250, i -> "user." + Long.toHexString(mix(i)) + "@mail-" + i % 17 + ".example.com"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void javaTrainsJnisFsstTable(String name, int rows, IntFunction<String> values, @TempDir Path tmp)
            throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, rows, values);
        List<String> tree = EncodingTrees.of(jniFile);
        assertThat(tree).as("vortex-jni picked FSST for this column").anyMatch(l -> l.contains("vortex.fsst"));

        // When
        writeWithJava(javaFile, rows, values);

        // Then
        List<byte[]> expected = EncodingTrees.firstFlatBuffers(jniFile);
        List<byte[]> actual = EncodingTrees.firstFlatBuffers(javaFile);
        assertThat(actual.get(0)).as("symbols").isEqualTo(expected.get(0));
        assertThat(actual.get(1)).as("symbol lengths").isEqualTo(expected.get(1));
        assertThat(actual.get(2)).as("code stream").isEqualTo(expected.get(2));
    }

    private static long mix(long i) {
        long z = (i + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (z ^ (z >>> 31)) & Long.MAX_VALUE;
    }

    private static Arguments shape(String name, int rows, IntFunction<String> values) {
        return Arguments.of(name, rows, values);
    }

    private static void writeWithJni(Path path, int rows, IntFunction<String> values) throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("v", new ArrowType.Utf8())));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            VarCharVector vector = (VarCharVector) root.getVector("v");
            vector.allocateNew();
            for (int i = 0; i < rows; i++) {
                vector.setSafe(i, values.apply(i).getBytes(StandardCharsets.UTF_8));
            }
            vector.setValueCount(rows);
            root.setRowCount(rows);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static void writeWithJava(Path path, int rows, IntFunction<String> values) throws IOException {
        String[] strings = new String[rows];
        for (int i = 0; i < rows; i++) {
            strings[i] = values.apply(i);
        }
        DType.Struct schema = new DType.Struct(List.of(V), List.of(DTypes.UTF8), false);
        WriteOptions options = WriteOptions.defaults().withGlobalDict(false)
                .withColumnEncoding(V, ColumnEncoding.candidates(EncodingId.VORTEX_FSST, EncodingId.VORTEX_VARBIN));
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, options)) {
            writer.writeChunk(Map.of(V, strings));
        }
    }
}
