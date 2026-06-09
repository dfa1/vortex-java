package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.UnknownArray;
import io.github.dfa1.vortex.encoding.Registry;
import io.github.dfa1.vortex.io.VortexReader;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Verifies Registry.allowUnknown() passthrough behaviour end-to-end
/// using a JNI-written file whose encodings are deliberately absent from the registry.
class AllowUnknownIntegrationTest {

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
    void allowUnknown_emptyRegistry_allColumnsReturnUnknownArray(@TempDir Path tmp) throws IOException {
        // Given — JNI writes a real file; empty registry has no decoders
        Path file = tmp.resolve("test.vtx");
        writeJni(file, 10_000);

        // When
        var totalRows = new AtomicLong();
        var allUnknown = new AtomicBoolean(true);
        var chunkCount = new AtomicLong();
        try (VortexReader vf = VortexReader.open(file, Registry.builder().allowUnknown().build());
             var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                totalRows.addAndGet(c.rowCount());
                chunkCount.incrementAndGet();
                for (Array col : c.columns().values()) {
                    if (!(col instanceof UnknownArray)) {
                        allUnknown.set(false);
                    }
                }
            });
        }

        // Then — every column value is UnknownArray; file was readable without throwing
        assertThat(chunkCount).hasPositiveValue();
        assertThat(totalRows).hasValue(10_000L);
        assertThat(allUnknown).describedAs("every column must be UnknownArray").isTrue();
    }

    @Test
    void strictMode_emptyRegistry_throwsVortexException(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("test.vtx");
        writeJni(file, 1_000);

        // When / Then — strict mode throws rather than returning UnknownArray
        assertThatThrownBy(() -> {
            try (VortexReader vf = VortexReader.open(file, Registry.empty());
                 var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.all())) {
                iter.forEachRemaining(c -> {});
            }
        }).isInstanceOf(VortexException.class);
    }

    @Test
    void allowUnknown_loadAllRegistry_noUnknownArrayForSupportedEncodings(@TempDir Path tmp) throws IOException {
        // Given — loadAll() has decoders for everything the JNI writer produces
        Path file = tmp.resolve("test.vtx");
        writeJni(file, 10_000);

        // When
        var chunkCount = new AtomicLong();
        var anyUnknown = new AtomicBoolean(false);
        try (VortexReader vf = VortexReader.open(file,
                Registry.builder().registerServiceLoaded().allowUnknown().build());
             var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                chunkCount.incrementAndGet();
                for (Array col : c.columns().values()) {
                    if (col instanceof UnknownArray) {
                        anyUnknown.set(true);
                    }
                }
            });
        }

        // Then — allowUnknown() is a no-op when all encodings are registered; no UnknownArray
        assertThat(chunkCount).hasPositiveValue();
        assertThat(anyUnknown).describedAs("fully-supported file should not produce UnknownArray").isFalse();
    }
}
