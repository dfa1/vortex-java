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
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// A bool array of one value becomes `vortex.constant` only when that is smaller than its packed
/// bits: Rust's compressor keeps a short one as `vortex.bool`, so the three flags of a zone table,
/// and a handful of rows, stay bool while a long constant column does not (#583).
class ConstantBoolInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 8, 9, 16, 17, 24, 64, 65, 512, 20_000})
    void javaWritesAConstantBoolAsJniDoes(int rows, @TempDir Path tmp) throws Exception {
        // Given — one column of identical bools
        Schema arrow = new Schema(List.of(Field.notNullable("flag", ArrowType.Bool.INSTANCE)));
        Path jni = tmp.resolve("jni.vortex");
        try (VortexWriter w = VortexWriter.builder(SESSION, jni.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
            BitVector flag = (BitVector) root.getVector("flag");
            root.allocateNew();
            for (int i = 0; i < rows; i++) {
                flag.setSafe(i, 1);
            }
            root.setRowCount(rows);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema as = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, as);
                w.writeBatch(array.memoryAddress(), as.memoryAddress());
            }
        }
        Path written = tmp.resolve("java.vortex");
        DType.Struct schema = new DType.Struct(List.of(ColumnName.of("flag")), List.of(DTypes.BOOL), false);
        boolean[] flags = new boolean[rows];
        Arrays.fill(flags, true);
        try (FileChannel ch = FileChannel.open(written, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            w.writeChunk(Map.of(ColumnName.of("flag"), flags));
        }

        // When
        List<String> result = EncodingTrees.withStats(written);

        // Then
        List<String> expected = EncodingTrees.withStats(jni);
        assertThat(result).isEqualTo(expected);
    }
}
