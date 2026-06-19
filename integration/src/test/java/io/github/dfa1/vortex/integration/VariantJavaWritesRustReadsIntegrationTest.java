package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.proto.Primitive;
import io.github.dfa1.vortex.proto.Scalar;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.VariantData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-compatibility for `vortex.variant` (Layer A): Java writes a constant variant
/// column, the Rust (JNI) reader opens the file and reports the correct row count and
/// schema. Arrow has no native variant type, so this asserts the file parses end-to-end
/// against the reference reader rather than decoding per-row values.
class VariantJavaWritesRustReadsIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    private static final DType.Struct VARIANT_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(new DType.Variant(false)),
            false);

    static {
        NativeLoader.loadJni();
    }

    @Test
    void javaWriter_jniReader_constantVariantColumn(@TempDir Path tmp) throws IOException {
        // Given — a constant variant column: every one of N rows is the i32 variant value 7.
        // Built as Rust's Scalar::variant(Scalar::primitive(7i32)): the inner Scalar carries
        // its own i32 dtype so the reference reader knows the wrapped value's type.
        Path file = tmp.resolve("java_variant.vtx");
        int rows = 5;
        VariantData data = VariantData.constant(rows, i32Variant(7L));

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, VARIANT_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then — the Rust reader parses the variant layout and agrees on row count + schema.
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        OptionalLong count = ds.rowCount().asOptional();
        assertThat(count).hasValue(rows);

        Schema schema = ds.arrowSchema(ALLOCATOR);
        assertThat(schema.getFields()).extracting(Field::getName).contains("v");
    }

    @Test
    void javaWriter_jniReader_varyingVariantColumn(@TempDir Path tmp) throws IOException {
        // Given — a non-constant variant column: distinct per-row i32 values. The encoder
        // lays this out as core_storage = vortex.chunked of one vortex.constant per row,
        // exactly the representation the Rust reference uses for a row-varying variant array.
        Path file = tmp.resolve("java_variant_varying.vtx");
        List<Scalar> values = List.of(i32Variant(10L), i32Variant(20L), i32Variant(30L), i32Variant(40L));
        VariantData data = new VariantData(values);

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, VARIANT_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then — the Rust reader parses the chunked variant layout and agrees on row count + schema.
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        assertThat(ds.rowCount().asOptional()).hasValue(values.size());
        assertThat(ds.arrowSchema(ALLOCATOR).getFields()).extracting(Field::getName).contains("v");
    }

    @Test
    void javaWriter_jniReader_shreddedVariantColumn(@TempDir Path tmp) throws IOException {
        // Given — a variant column with a row-aligned shredded i32 projection. The container
        // gains a second (shredded) typed child plus shredded_dtype in its metadata.
        Path file = tmp.resolve("java_variant_shredded.vtx");
        List<Scalar> values = List.of(i32Variant(10L), i32Variant(20L), i32Variant(30L));
        VariantData data = VariantData.shredded(
                values, new int[]{10, 20, 30}, new DType.Primitive(PType.I32, false));

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, VARIANT_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then — the Rust reader parses the shredded variant layout and agrees on row count + schema.
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        assertThat(ds.rowCount().asOptional()).hasValue(values.size());
        assertThat(ds.arrowSchema(ALLOCATOR).getFields()).extracting(Field::getName).contains("v");
    }

    private static Scalar i32Variant(long value) {
        // Inner typed scalar carrying its own i32 dtype, wrapped as a variant value
        // (mirrors Rust Scalar::variant(Scalar::primitive(value))).
        return new Scalar(
                io.github.dfa1.vortex.proto.DType.ofPrimitive(
                        new Primitive(io.github.dfa1.vortex.proto.PType.I32, false)),
                ScalarValue.ofInt64Value(value));
    }
}
