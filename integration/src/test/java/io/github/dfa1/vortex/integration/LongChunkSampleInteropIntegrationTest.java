package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust 0.95 samples an array whose 1% spans 8 FastLanes chunks or more as whole, aligned
/// 1024-row chunks (`sample_rows`), not as 64-row strides joined from across the array. Only a
/// chunk of 723,200 rows or more gets there, which a 1-byte column's 1 MiB chunk is. The strides
/// cut runs the aligned chunks keep, so on runs of some lengths the two samples pick different
/// encodings.
class LongChunkSampleInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int ROWS = 2 * 1024 * 1024;
    private static final long[] RANDOM = new Random(7).longs(ROWS).toArray();

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest(name = "runs of {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 8, 12, 16, 24, 32, 48, 64, 100})
    void javaWritesTheEncodingTreeJniWrites(int runLength, @TempDir Path tmp) throws Exception {
        // Given
        byte[] values = new byte[ROWS];
        for (int i = 0; i < ROWS; i++) {
            values[i] = (byte) Math.floorMod(RANDOM[i / runLength], 100);
        }
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, values);
        List<String> expected = EncodingTrees.of(jniFile);

        // When
        DType.Struct schema = new DType.Struct(List.of(V), List.of(new DType.Primitive(PType.I8, false)), false);
        try (var channel = FileChannel.open(javaFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(V, values));
        }

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(expected);
    }

    private static void writeWithJni(Path path, byte[] values) throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("v", new ArrowType.Int(8, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            TinyIntVector vector = (TinyIntVector) root.getVector("v");
            vector.allocateNew(ROWS);
            for (int i = 0; i < ROWS; i++) {
                vector.set(i, values[i]);
            }
            vector.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }
}
