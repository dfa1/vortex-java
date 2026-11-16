package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust narrows `vortex.varbin` offsets to the smallest integer type holding the data buffer's
/// length: u8 under 256 bytes, u16 under 64 KiB. Java read every offset type but i32/u32 as 64-bit,
/// so a small Rust-written binary column decoded as garbage offsets and failed (#721).
class VarBinNarrowOffsetsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName B = ColumnName.of("b");

    static {
        NativeLoader.loadJni();
    }

    /// 3-byte values: 50 rows fill a 150-byte buffer (u8 offsets), 1000 a 3000-byte one (u16).
    @ParameterizedTest(name = "{0} rows")
    @ValueSource(ints = {50, 1000})
    void javaReadsTheNarrowOffsetsJniWrote(int rows, @TempDir Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("jni.vortex");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            expected.add(HexFormat.of().formatHex(value(i)));
        }
        writeWithJni(file, rows);

        // When
        List<String> result = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.all())) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    VarBinArray values = chunk.column(B);
                    for (int i = 0; i < chunk.rowCount(); i++) {
                        result.add(HexFormat.of().formatHex(values.getBytes(i)));
                    }
                }
            }
        }

        // Then — the file must really hold a varbin, or the test proves nothing
        assertThat(String.join("\n", EncodingTrees.of(file))).contains("vortex.varbin");
        assertThat(result).isEqualTo(expected);
    }

    private static byte[] value(int row) {
        return new byte[]{(byte) row, (byte) (row >> 8), 7};
    }

    private static void writeWithJni(Path path, int rows) throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("b", ArrowType.Binary.INSTANCE)));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            VarBinaryVector vector = (VarBinaryVector) root.getVector("b");
            vector.allocateNew();
            for (int i = 0; i < rows; i++) {
                vector.setSafe(i, value(i));
            }
            vector.setValueCount(rows);
            root.setRowCount(rows);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }
}
