package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.calcite.VortexCalcite;
import io.github.dfa1.vortex.calcite.VortexTable;
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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// SQL over a vortex-jni-written file through the Calcite adapter: what the planner is told about
/// the table must match what Rust wrote.
class CalciteInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    // several batches, so the row count is a sum over chunks, not a single chunk's length
    private static final int BATCHES = 3;
    private static final int BATCH_ROWS = 1_000;

    static {
        NativeLoader.loadJni();
    }

    @Test
    void jniWriter_tableStatistic_isTheRowCountRustWrote(@TempDir Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("jni.vortex");
        writeWithJni(file);
        VortexTable sut = new VortexTable(file);

        // When
        Double result = sut.getStatistic().getRowCount();

        // Then — and SQL agrees with the statistic
        assertThat(result).isEqualTo((double) BATCHES * BATCH_ROWS);
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from vtx.t")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo((long) BATCHES * BATCH_ROWS);
        }
    }

    private static void writeWithJni(Path file) throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("id", new ArrowType.Int(64, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    ids.allocateNew(BATCH_ROWS);
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        ids.set(i, (long) batch * BATCH_ROWS + i);
                    }
                    root.setRowCount(BATCH_ROWS);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                         ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                        writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
                    }
                }
            }
        }
    }
}
