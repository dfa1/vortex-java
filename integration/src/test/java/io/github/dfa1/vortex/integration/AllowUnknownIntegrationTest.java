package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.UnknownArray;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanResult;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Verifies EncodingRegistry.allowUnknown() passthrough behaviour end-to-end
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

    private static List<ScanResult> scanAll(VortexReader vf) {
        var results = new ArrayList<ScanResult>();
        var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.all());
        while (iter.hasNext()) {
            results.add(iter.next());
        }
        return results;
    }

    @Test
    void allowUnknown_emptyRegistry_allColumnsReturnUnknownArray(@TempDir Path tmp) throws IOException {
        // Given — JNI writes a real file; empty registry has no decoders
        Path file = tmp.resolve("test.vtx");
        writeJni(file, 10_000);

        // When
        List<ScanResult> results;
        try (VortexReader vf = VortexReader.open(file, EncodingRegistry.builder().allowUnknown().build())) {
            results = scanAll(vf);
        }

        // Then — every column value is UnknownArray; file was readable without throwing
        assertThat(results).isNotEmpty();
        long totalRows = results.stream().mapToLong(ScanResult::rowCount).sum();
        assertThat(totalRows).isEqualTo(10_000);
        for (ScanResult r : results) {
            for (Array col : r.columns().values()) {
                assertThat(col)
                        .describedAs("column should be UnknownArray when no decoder registered")
                        .isInstanceOf(UnknownArray.class);
            }
        }
    }

    @Test
    void strictMode_emptyRegistry_throwsVortexException(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("test.vtx");
        writeJni(file, 1_000);

        // When / Then — strict mode throws rather than returning UnknownArray
        assertThatThrownBy(() -> {
            try (VortexReader vf = VortexReader.open(file, EncodingRegistry.empty())) {
                scanAll(vf);
            }
        }).isInstanceOf(VortexException.class);
    }

    @Test
    void allowUnknown_loadAllRegistry_noUnknownArrayForSupportedEncodings(@TempDir Path tmp) throws IOException {
        // Given — loadAll() has decoders for everything the JNI writer produces
        Path file = tmp.resolve("test.vtx");
        writeJni(file, 10_000);

        // When
        List<ScanResult> results;
        try (VortexReader vf = VortexReader.open(file, EncodingRegistry.builder().registerServiceLoaded().allowUnknown().build())) {
            results = scanAll(vf);
        }

        // Then — allowUnknown() is a no-op when all encodings are registered; no UnknownArray
        assertThat(results).isNotEmpty();
        for (ScanResult r : results) {
            for (Array col : r.columns().values()) {
                assertThat(col)
                        .describedAs("fully-supported file should not produce UnknownArray")
                        .isNotInstanceOf(UnknownArray.class);
            }
        }
    }
}
