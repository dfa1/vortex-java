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
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
    private static final LocalDate DATE = LocalDate.of(2024, 2, 29);
    private static final LocalTime TIME = LocalTime.of(13, 45, 30);
    // pre-epoch on purpose: millisecond truncation must floor, not round toward zero
    private static final Instant INSTANT = Instant.parse("1969-12-31T23:59:59.250Z");

    static {
        NativeLoader.loadJni();
    }

    @Test
    void jniWriter_tableStatistic_isTheRowCountRustWrote(@TempDir Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("jni.vortex");
        writeIdsWithJni(file, BATCHES, BATCH_ROWS);
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

    @Test
    void jniWriter_filteredCountMinMax_foldFromRustZones(@TempDir Path tmp) throws Exception {
        // Given — Rust writes fixed 8192-row vortex.zoned zones over chunks of its own sizing; the
        // fold needed one zone per chunk, so every filtered aggregate on such a file scanned
        Path file = tmp.resolve("jni_zoned.vortex");
        writeIdsWithJni(file, 8, 25_000);
        long lo = 10_000;
        long hi = 170_000;
        String sql = "select count(*) c, min(id) mn, max(id) mx from vtx.t where id between " + lo + " and " + hi;

        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            // When
            String plan;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("explain plan for " + sql)) {
                rs.next();
                plan = rs.getString(1);
            }

            // Then — answered from zones and boundary rows, and equal to the data
            assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan");
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("c")).isEqualTo(hi - lo + 1);
                assertThat(rs.getLong("mn")).isEqualTo(lo);
                assertThat(rs.getLong("mx")).isEqualTo(hi);
            }
        }
    }

    @Test
    void jniWriter_temporalDecimalAndBinaryColumns_readThroughSql(@TempDir Path tmp) throws Exception {
        // Given — a file with a timestamp could not be queried at all: the row type threw
        Path file = tmp.resolve("jni_types.vortex");
        writeTypesWithJni(file);
        String sql = "select `day`, at, wall, `instant`, amount, blob from vtx.t";

        // When
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet result = st.executeQuery(sql)) {

            // Then
            assertThat(result.next()).isTrue();
            assertThat(result.getDate("day").toLocalDate()).isEqualTo(DATE);
            assertThat(result.getTime("at").toLocalTime()).isEqualTo(TIME);
            assertThat(result.getTimestamp("wall").toLocalDateTime())
                    .isEqualTo(LocalDateTime.ofInstant(INSTANT, ZoneOffset.UTC));
            assertThat(result.getTimestamp("instant").toLocalDateTime())
                    .isEqualTo(LocalDateTime.ofInstant(INSTANT, ZoneOffset.UTC));
            assertThat(result.getBigDecimal("amount")).isEqualByComparingTo("12.34");
            assertThat(result.getBytes("blob")).containsExactly(1, 2, 3);
            assertThat(result.next()).isFalse();
        }
    }

    private static void writeTypesWithJni(Path file) throws Exception {
        Schema schema = new Schema(List.of(
                Field.notNullable("day", new ArrowType.Date(DateUnit.DAY)),
                Field.notNullable("at", new ArrowType.Time(TimeUnit.MILLISECOND, 32)),
                Field.notNullable("wall", new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)),
                Field.notNullable("instant", new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")),
                Field.notNullable("amount", new ArrowType.Decimal(10, 2, 128)),
                Field.notNullable("blob", new ArrowType.Binary())));
        long micros = INSTANT.getEpochSecond() * 1_000_000 + INSTANT.getNano() / 1_000;
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            root.allocateNew();
            ((DateDayVector) root.getVector("day")).setSafe(0, Math.toIntExact(DATE.toEpochDay()));
            ((TimeMilliVector) root.getVector("at")).setSafe(0, (int) (TIME.toNanoOfDay() / 1_000_000));
            ((TimeStampMicroVector) root.getVector("wall")).setSafe(0, micros);
            ((TimeStampMicroTZVector) root.getVector("instant")).setSafe(0, micros);
            ((DecimalVector) root.getVector("amount")).setSafe(0, new BigDecimal("12.34"));
            ((VarBinaryVector) root.getVector("blob")).setSafe(0, new byte[]{1, 2, 3});
            root.setRowCount(1);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static void writeIdsWithJni(Path file, int batches, int batchRows) throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("id", new ArrowType.Int(64, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < batches; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    ids.allocateNew(batchRows);
                    for (int i = 0; i < batchRows; i++) {
                        ids.set(i, (long) batch * batchRows + i);
                    }
                    root.setRowCount(batchRows);
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
