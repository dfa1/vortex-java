package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/// Writes files with vortex-jni for the interop tests.
final class JniFiles {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final Schema ID_VALUE = new Schema(List.of(
            Field.notNullable("id", new ArrowType.Int(64, true)),
            Field.notNullable("value", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))));

    static {
        NativeLoader.loadJni();
    }

    private JniFiles() {
    }

    /// Hands one Arrow batch to a vortex-jni writer through the Arrow C data interface.
    static void writeBatch(BufferAllocator allocator, VortexWriter writer, VectorSchemaRoot root)
            throws IOException {
        try (ArrowArray array = ArrowArray.allocateNew(allocator);
             ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
            Data.exportVectorSchemaRoot(allocator, root, null, array, schema);
            writer.writeBatch(array.memoryAddress(), schema.memoryAddress());
        }
    }

    /// Writes `rows` rows in one batch: `id` (i64) counting from 0, and `value` (f64) seeded random
    /// in `[0, 1000)`.
    static void writeIdsAndRandomValues(Path file, int rows) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        var rng = new Random(42L);
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, ID_VALUE, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(ID_VALUE, ALLOCATOR)) {
            BigIntVector idVec = (BigIntVector) root.getVector("id");
            Float8Vector valVec = (Float8Vector) root.getVector("value");
            idVec.allocateNew(rows);
            valVec.allocateNew(rows);
            for (int i = 0; i < rows; i++) {
                idVec.setSafe(i, i);
                valVec.setSafe(i, rng.nextDouble() * 1000.0);
            }
            root.setRowCount(rows);
            writeBatch(ALLOCATOR, writer, root);
        }
    }
}
