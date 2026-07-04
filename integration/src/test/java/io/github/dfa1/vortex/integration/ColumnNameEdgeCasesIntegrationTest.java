package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.VortexReader;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// Cross-compatibility for column-name edge cases nobody advertises, measured against the
/// Rust (JNI) reference:
///
/// - Blank names (`""`, whitespace-only) are wire-legal — the Rust writer produces them — but
///   vortex-java refuses them BOTH ways by policy: the writer never emits them and the reader
///   rejects files carrying them with a message pointing at the producing pipeline. Stricter
///   than the wire on purpose, like a JSON library refusing a `""` key it could technically
///   parse (see `VortexWriterTest` / `DTypeStructBuilderTest` / `PostscriptParserDTypeGuardsTest`).
/// - Duplicate field names are legal in Rust's in-memory `StructFields`
///   (`vortex-array/src/dtype/struct_.rs`, first-match name access) but REJECTED by its file
///   writer: "StructLayout must have unique field names" — the wire contract both writers
///   enforce; our reader rejects such files too.
class ColumnNameEdgeCasesIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    static {
        NativeLoader.loadJni();
    }

    @Test
    void jniWritesEmptyColumnName_javaRejectsItByPolicy(@TempDir Path tmp) throws IOException {
        // Given — the Rust (JNI) writer legitimately produces a file whose first column is
        // named "" (the wire format permits it)
        Path file = tmp.resolve("jni_empty_name.vortex");
        Schema schema = new Schema(List.of(
                Field.notNullable("", new ArrowType.Int(64, true)),
                Field.notNullable("x", new ArrowType.Int(64, true))));
        writeJni(file, schema, new long[][]{{1, 2}, {30, 40}});

        // When
        Throwable result = catchThrowable(() -> {
            try (var reader = VortexReader.open(file)) {
                assertThat(reader).isNotNull();
            }
        });

        // Then — deliberate strictness beyond the wire: a blank name is almost certainly a bug
        // in the producing pipeline, and rejecting it loudly beats propagating an unusable name
        // into name-keyed APIs and SQL identifiers
        assertThat(result)
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("invalid field name in file schema")
                .hasMessageContaining("blank field name");
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
}
