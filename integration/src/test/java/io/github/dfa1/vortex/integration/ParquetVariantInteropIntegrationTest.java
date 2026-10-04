package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VariantArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// vortex-jni's default writer turns an Arrow `arrow.parquet.variant` column into
/// `vortex.variant` over `vortex.parquet.variant` (`core2026.08.3`, Rust's default edition);
/// vortex-java must read every row's Variant binaries back (#445).
///
/// Rows mix an int8 and a short-string Variant so a reader that assumed one value type per
/// column would fail, and every 7th row is null so the row validity child is exercised.
class ParquetVariantInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 1_000;
    /// Variant metadata with an empty dictionary: version 1, no keys.
    private static final byte[] EMPTY_METADATA = {0x01, 0x00};

    static {
        NativeLoader.loadJni();
    }

    @Test
    void jniWritesParquetVariant_javaReadsEveryRow(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("parquet_variant.vortex");
        writeJni(file);

        // When
        List<byte[]> result = readValues(file);

        // Then — vortex-jni chose the encoding under test, and every row's binary survives
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(InspectorTree.build(vf).usedEncodings()).contains("vortex.parquet.variant");
        }
        assertThat(result).hasSize(ROWS);
        for (int i = 0; i < ROWS; i++) {
            assertThat(result.get(i)).as("row %d", i).isEqualTo(expectedValue(i));
        }
    }

    @Test
    void jniWritesShreddedParquetVariant_javaReadsValueAndTypedValue(@TempDir Path tmp) throws IOException {
        // Given — shredded storage: even rows carry the int in `typed_value` with a null `value`,
        // odd rows the reverse, so the nullable `value` child must decode
        Path file = tmp.resolve("parquet_variant_shredded.vortex");
        writeShreddedJni(file);

        // When
        List<String> result = readShredded(file);

        // Then
        assertThat(result).hasSize(ROWS);
        for (int i = 0; i < ROWS; i++) {
            assertThat(result.get(i)).as("row %d", i).isEqualTo(i % 2 == 0 ? "typed:" + i : "value:" + i);
        }
    }

    /// Apache Variant value for row `i`, or `null` for a null row: an int8 primitive (header
    /// `0x0c` = type 3 << 2 | basic type 0) on even rows, a short string (header = length << 2 |
    /// basic type 1) on odd rows.
    private static byte[] expectedValue(int i) {
        if (i % 7 == 0) {
            return null;
        }
        return int8OrString(i);
    }

    private static byte[] int8OrString(int i) {
        if (i % 2 == 0) {
            return new byte[]{0x0c, (byte) i};
        }
        byte[] s = ("s" + i % 10).getBytes(StandardCharsets.UTF_8);
        byte[] v = new byte[s.length + 1];
        v[0] = (byte) (s.length << 2 | 1);
        System.arraycopy(s, 0, v, 1, s.length);
        return v;
    }

    private static void writeJni(Path file) throws IOException {
        Field metadata = Field.notNullable("metadata", ArrowType.Binary.INSTANCE);
        Field value = Field.notNullable("value", ArrowType.Binary.INSTANCE);
        FieldType variantType = new FieldType(true, ArrowType.Struct.INSTANCE, null,
                Map.of("ARROW:extension:name", "arrow.parquet.variant", "ARROW:extension:metadata", ""));
        Schema schema = new Schema(List.of(new Field("v", variantType, List.of(metadata, value))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toUri().toString(), schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            var struct = (StructVector) root.getVector("v");
            struct.allocateNew();
            var metadataVector = (VarBinaryVector) struct.getChild("metadata");
            var valueVector = (VarBinaryVector) struct.getChild("value");
            for (int i = 0; i < ROWS; i++) {
                byte[] v = expectedValue(i);
                // Non-null children under a null struct row still need a value; any valid Variant does.
                metadataVector.setSafe(i, EMPTY_METADATA);
                valueVector.setSafe(i, v == null ? new byte[]{0x00} : v);
                if (v == null) {
                    struct.setNull(i);
                } else {
                    struct.setIndexDefined(i);
                }
            }
            metadataVector.setValueCount(ROWS);
            valueVector.setValueCount(ROWS);
            struct.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    /// Reads each row's Variant `value` binary (asserting its `metadata` on the way), or `null`
    /// for a null row.
    private static List<byte[]> readValues(Path file) throws IOException {
        List<byte[]> rows = new ArrayList<>(ROWS);
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(ScanOptions.columns("v"))) {
            while (iter.hasNext()) {
                Array column = iter.next().column("v");
                Array storage = ((VariantArray) column).coreStorage();
                MaskedArray masked = storage instanceof MaskedArray m ? m : null;
                StructArray struct = (StructArray) (masked != null ? masked.inner() : storage);
                VarBinArray metadata = (VarBinArray) struct.field(0);
                VarBinArray value = (VarBinArray) struct.field(1);
                for (long i = 0; i < struct.length(); i++) {
                    if (masked != null && !masked.isValid(i)) {
                        rows.add(null);
                        continue;
                    }
                    assertThat(metadata.getBytes(i)).isEqualTo(EMPTY_METADATA);
                    rows.add(value.getBytes(i));
                }
            }
        }
        return rows;
    }
    private static void writeShreddedJni(Path file) throws IOException {
        Field metadata = Field.notNullable("metadata", ArrowType.Binary.INSTANCE);
        Field value = Field.nullable("value", ArrowType.Binary.INSTANCE);
        Field typedValue = Field.nullable("typed_value", new ArrowType.Int(32, true));
        FieldType variantType = new FieldType(false, ArrowType.Struct.INSTANCE, null,
                Map.of("ARROW:extension:name", "arrow.parquet.variant", "ARROW:extension:metadata", ""));
        Schema schema = new Schema(List.of(new Field("v", variantType, List.of(metadata, value, typedValue))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toUri().toString(), schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            var struct = (StructVector) root.getVector("v");
            struct.allocateNew();
            var metadataVector = (VarBinaryVector) struct.getChild("metadata");
            var valueVector = (VarBinaryVector) struct.getChild("value");
            var typedVector = (IntVector) struct.getChild("typed_value");
            for (int i = 0; i < ROWS; i++) {
                metadataVector.setSafe(i, EMPTY_METADATA);
                if (i % 2 == 0) {
                    valueVector.setNull(i);
                    typedVector.setSafe(i, i);
                } else {
                    valueVector.setSafe(i, int8OrString(i));
                    typedVector.setNull(i);
                }
                struct.setIndexDefined(i);
            }
            metadataVector.setValueCount(ROWS);
            valueVector.setValueCount(ROWS);
            typedVector.setValueCount(ROWS);
            struct.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    /// Renders each row as `typed:<int>` when the shredded child holds it, else `value:<i>` after
    /// checking the `value` binary is the one written for row `i`. Rust's writer moves the Arrow
    /// `typed_value` out of `vortex.parquet.variant` into the canonical `vortex.variant`
    /// container's shredded child, so that is where the ints come back from.
    private static List<String> readShredded(Path file) throws IOException {
        List<String> rows = new ArrayList<>(ROWS);
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(ScanOptions.columns("v"))) {
            while (iter.hasNext()) {
                VariantArray column = iter.next().column("v");
                StructArray struct = (StructArray) column.coreStorage();
                VarBinArray value = (VarBinArray) struct.field(1);
                MaskedArray typed = (MaskedArray) column.shredded();
                for (long i = 0; i < struct.length(); i++) {
                    int row = rows.size();
                    if (typed.isValid(i)) {
                        rows.add("typed:" + ((IntArray) typed.inner()).getInt(i));
                    } else {
                        assertThat(value.getBytes(i)).isEqualTo(int8OrString(row));
                        rows.add("value:" + row);
                    }
                }
            }
        }
        return rows;
    }
}
