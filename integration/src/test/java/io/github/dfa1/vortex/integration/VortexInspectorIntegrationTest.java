package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.inspect.HtmlReport;
import io.github.dfa1.vortex.inspect.InspectorTree;
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
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.nio.file.Path;
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

    @SuppressWarnings("SameParameterValue")
    private static void writeJni(Path file, int rows) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        var rng = new Random(42L);
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, SCHEMA, ALLOCATOR).build()) {
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
        assertThat(result)
                .contains("Vortex v")
                .contains("id")
                .contains("value")
                .contains("Registered encodings:")
                .contains("Used encodings:")
                .contains("Layout:");
    }

    @Test
    void inspect_reportsMinMaxFromZoneMapTable(@TempDir Path tmp) throws IOException {
        // Given — the Rust writer puts bounds only in the vortex.zoned stats table, never in the
        // flat segments' array-level stats; the inspector used to read only the latter and
        // printed no min/max for any Rust-written file (#416). ids are 0..rows-1, so the
        // expected bounds are exact.
        Path file = tmp.resolve("zoned.vtx");
        writeJni(file, 50_000);

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = VortexInspector.inspect(vf);
        }

        // Then
        assertThat(result).containsPattern("id: .*min=0 max=49999");
    }

    @Test
    void htmlReport_reportsChunkMinMaxFromZoneMapTable(@TempDir Path tmp) throws IOException {
        // Given — same #416 gap in the HTML report: its per-chunk table read only array-level
        // stats, so a Rust-written single-chunk column showed empty min/max cells.
        Path file = tmp.resolve("zoned.vtx");
        writeJni(file, 50_000);

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = HtmlReport.render(InspectorTree.build(vf), "zoned.vtx");
        }

        // Then
        assertThat(result).contains("<td><code>0</code></td><td><code>49999</code></td>");
    }

    @Test
    void htmlReport_globalDictColumn_chunkMinMaxAreValuesNotCodes(@TempDir Path tmp) throws IOException {
        // Given — a global-dictionary column stores per-chunk *codes* under its zone map; their
        // array-level min/max are code numbers (0, 1, ...), not values. Chunk 0 holds only
        // "apple"/"banana" and chunk 1 only "cherry"/"date", so each chunk row must show its own
        // value range, which only the zone-map table carries.
        Path file = tmp.resolve("dict.vtx");
        ColumnName fruit = ColumnName.of("fruit");
        var schema = new DType.Struct(List.of(fruit), List.of(DType.UTF8), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(fruit, repeat(1_000, "apple", "banana")));
            writer.writeChunk(Map.of(fruit, repeat(1_000, "cherry", "date")));
        }

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = HtmlReport.render(InspectorTree.build(vf), "dict.vtx");
        }

        // Then
        assertThat(result)
                .contains("<td><code>apple</code></td><td><code>banana</code></td>")
                .contains("<td><code>cherry</code></td><td><code>date</code></td>");
    }

    private static String[] repeat(int rows, String a, String b) {
        String[] out = new String[rows];
        for (int i = 0; i < rows; i++) {
            out[i] = i % 2 == 0 ? a : b;
        }
        return out;
    }
}
