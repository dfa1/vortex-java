package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The cascade picks the scheme Rust's compressor picks for a non-null `I64` column: each shape
/// goes through vortex-jni and vortex-java, and the two files must encode the column's data the
/// same way. Each shape pins one rule of Rust's selection that Java once got wrong (#604). Only
/// the data is compared: the zone stats tables are `NullableIntegerInteropIntegrationTest`'s.
class CascadeChoiceInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final long[] RANDOM = new Random(7).longs(4 * 8192).toArray();

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> shapes() {
        // runs of 48 wide values, every third run 0: a dict layout with run-end codes, as Rust
        // writes it. Costing run-end's ends by their estimate instead of what they encode to made
        // a plain run-end look smaller than the dict
        long[] runs = new long[1024];
        for (int i = 0; i < runs.length; i++) {
            int run = i / 48;
            runs[i] = run % 3 == 0 ? 0 : RANDOM[run] >>> 20;
        }
        return Stream.of(Arguments.of("runs with a repeated value", runs));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void javaEncodesTheDataAsJniDoes(String name, long[] values, @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, values);
        List<String> expected = dataTree(jniFile);

        // When
        writeWithJava(javaFile, values);

        // Then
        assertThat(dataTree(javaFile)).isEqualTo(expected);
    }

    /// The tree down to the zone stats table: the zoned layout's second flat child.
    private static List<String> dataTree(Path file) throws IOException {
        List<String> tree = EncodingTrees.of(file);
        int zones = tree.lastIndexOf("    vortex.flat");
        return tree.subList(0, zones);
    }

    private static void writeWithJni(Path path, long[] values) throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("v", new ArrowType.Int(64, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            BigIntVector vector = (BigIntVector) root.getVector("v");
            vector.allocateNew(values.length);
            for (int i = 0; i < values.length; i++) {
                vector.set(i, values[i]);
            }
            vector.setValueCount(values.length);
            root.setRowCount(values.length);
            JniFiles.writeBatch(ALLOCATOR, writer, root);
        }
    }

    private static void writeWithJava(Path path, long[] values) throws IOException {
        DType.Struct schema = new DType.Struct(List.of(V), List.of(new DType.Primitive(PType.I64, false)), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(V, values));
        }
    }
}
