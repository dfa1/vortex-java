package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// Cross-compatibility for column-name edge cases nobody advertises, measured against the
/// Rust (JNI) reference:
///
/// - `""` is a legal column name: round-trips in BOTH directions (Rust writes/Java reads and
///   Java writes/Rust reads).
/// - Duplicate field names are legal in Rust's in-memory `StructFields`
///   (`vortex-array/src/dtype/struct_.rs`, first-match name access) but REJECTED by its file
///   writer: "StructLayout must have unique field names" — the wire contract both writers
///   must enforce. Reader-side handling of foreign duplicate-name files is tracked in
///   TODO.md §"Column identity".
class ColumnNameEdgeCasesIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    static {
        NativeLoader.loadJni();
    }

    @Test
    void jniWritesEmptyColumnName_javaReadsIt(@TempDir Path tmp) throws IOException {
        // Given — the Rust (JNI) writer produces a file whose first column is named ""
        Path file = tmp.resolve("jni_empty_name.vortex");
        Schema schema = new Schema(List.of(
                Field.notNullable("", new ArrowType.Int(64, true)),
                Field.notNullable("x", new ArrowType.Int(64, true))));
        writeJni(file, schema, new long[][]{{1, 2}, {30, 40}});

        // When
        Map<String, long[]> result = javaReadAllLongColumns(file);

        // Then — the empty name is a plain map key, values intact
        assertThat(result.keySet()).containsExactlyInAnyOrder("", "x");
        assertThat(result.get("")).containsExactly(1, 2);
        assertThat(result.get("x")).containsExactly(30, 40);
    }

    @Test
    void javaWritesEmptyColumnName_jniReadsIt(@TempDir Path tmp) throws IOException {
        // Given — the Java writer produces a file with an empty column name (the Struct record
        // constructor performs no name validation; only StructBuilder rejects duplicates)
        Path file = tmp.resolve("java_empty_name.vortex");
        var dtype = new DType.Struct(
                List.of("", "x"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.I64, false)),
                false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(
                     ch, dtype, io.github.dfa1.vortex.writer.WriteOptions.defaults())) {
            writer.writeChunk(Map.of("", new long[]{1, 2}, "x", new long[]{30, 40}));
        }

        // When — the Rust (JNI) reader scans it
        List<String> result = new ArrayList<>();
        long rows = jniReadFieldNames(file, result);

        // Then — the reference reader accepts the file and reports the empty-named field
        assertThat(rows).isEqualTo(2);
        assertThat(result).containsExactly("", "x");
    }

    @Test
    void jniWriterRejectsDuplicateColumnNames(@TempDir Path tmp) {
        // Given — a schema with two fields named "dup". Rust's in-memory StructFields documents
        // duplicates as legal (first-match name access), but its FILE writer enforces uniqueness:
        // "StructLayout must have unique field names". This test pins that wire contract — the
        // Java writer must mirror the same rejection (see VortexWriterTest).
        Path file = tmp.resolve("jni_dup_names.vortex");
        Schema schema = new Schema(List.of(
                Field.notNullable("dup", new ArrowType.Int(64, true)),
                Field.notNullable("dup", new ArrowType.Int(64, true))));

        // When
        Throwable result = catchThrowable(() -> writeJni(file, schema, new long[][]{{1, 2}, {30, 40}}));

        // Then — the reference writer refuses to produce a duplicate-name file
        assertThat(result)
                .isInstanceOf(IOException.class)
                .hasRootCauseMessage("Vortex Error: Other error: StructLayout must have unique field names");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /// Writes one batch through the Rust (JNI) writer: one `long[]` per schema field, in order.
    private static void writeJni(Path file, Schema schema, long[][] columns) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, schema, new HashMap<>(), ALLOCATOR);
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            int n = columns[0].length;
            for (int c = 0; c < columns.length; c++) {
                BigIntVector vec = (BigIntVector) root.getVector(c);
                vec.allocateNew(n);
                for (int i = 0; i < n; i++) {
                    vec.setSafe(i, columns[c][i]);
                }
            }
            root.setRowCount(n);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    /// Scans the whole file with the Java reader, materializing every column as `long[]`.
    private static Map<String, long[]> javaReadAllLongColumns(Path file) throws IOException {
        Map<String, long[]> out = new HashMap<>();
        try (var reader = VortexReader.open(file);
             var iter = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    for (var entry : chunk.columns().entrySet()) {
                        LongArray col = (LongArray) entry.getValue();
                        long[] values = new long[(int) col.length()];
                        for (int i = 0; i < values.length; i++) {
                            values[i] = col.getLong(i);
                        }
                        out.put(entry.getKey(), values);
                    }
                }
            }
        }
        return out;
    }

    /// Scans the file with the Rust (JNI) reader, returning the row count and collecting the
    /// Arrow schema field names.
    private static long jniReadFieldNames(Path file, List<String> namesOut) throws IOException {
        long rows = 0;
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(ScanOptions.of());
        while (scan.hasNext()) {
            var partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    var root = reader.getVectorSchemaRoot();
                    rows += root.getRowCount();
                    if (namesOut.isEmpty()) {
                        for (Field field : root.getSchema().getFields()) {
                            namesOut.add(field.getName());
                        }
                    }
                }
            }
        }
        return rows;
    }
}
