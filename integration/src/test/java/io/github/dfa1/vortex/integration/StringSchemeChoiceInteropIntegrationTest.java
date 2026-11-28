package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
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

/// A default write picks the string scheme vortex-jni picks. FSST and OnPair are ranked on a
/// sample. Rust 0.95 trains OnPair on half a sample's bytes rather than the 15% a full chunk
/// trains on, which made OnPair look worse on the short columns, taken whole, where FSST wins;
/// and it charges a sample only its share of a symbol table or token dictionary
/// (`amortize_fixed_nbytes`), which turned the 5000-row columns from FSST to OnPair.
class StringSchemeChoiceInteropIntegrationTest {

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
                shape("emails whole", 250, i -> "user." + Long.toHexString(mix(i)) + "@mail-" + i % 17 + ".example.com"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void javaPicksTheSchemeJniPicks(String name, int rows, IntFunction<String> values, @TempDir Path tmp)
            throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, rows, values);

        // When
        writeWithJava(javaFile, rows, values);

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(EncodingTrees.of(jniFile));
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
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(V, strings));
        }
    }
}
