package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.VortexReader;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies that VortexInspector produces a correct report for a JNI-written file.
class VortexInspectorIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final Schema SCHEMA = new Schema(List.of(
            Field.notNullable("id", new ArrowType.Int(64, true)),
            Field.notNullable("value", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
    ));

    static {
        NativeLoader.loadJni();
    }

    private static void writeJni(Path file, int rows) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        var rng = new Random(42L);
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, SCHEMA, new HashMap<>(), ALLOCATOR)) {
            try (VectorSchemaRoot root = VectorSchemaRoot.create(SCHEMA, ALLOCATOR)) {
                BigIntVector idVec = (BigIntVector) root.getVector("id");
                Float8Vector valVec = (Float8Vector) root.getVector("value");
                idVec.allocateNew(rows);
                valVec.allocateNew(rows);
                for (int i = 0; i < rows; i++) {
                    idVec.setSafe(i, i);
                    valVec.setSafe(i, rng.nextDouble() * 1000.0);
                }
                root.setRowCount(rows);
                try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                     ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                    Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                    writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
                }
            }
        }
    }

    @Test
    void inspect_showsFileInfoAndEncodings(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("inspect.vtx");
        writeJni(file, 50_000);

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = VortexInspector.inspect(vf);
        }

        // Then
        System.out.println(result);
        assertThat(result).contains("Vortex v");
        assertThat(result).contains("id");
        assertThat(result).contains("value");
        assertThat(result).contains("Registered encodings:");
        assertThat(result).contains("Used encodings:");
        assertThat(result).contains("Layout:");
    }
}
