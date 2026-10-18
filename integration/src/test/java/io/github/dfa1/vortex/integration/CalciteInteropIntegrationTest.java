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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest(name = "where {0}")
    @CsvSource(delimiter = '|', value = {
            // an IN list arrives as an OR of equalities on one column
            "id in (3, 1500, 2999)        | 3, 1500, 2999",
            // a literal on the left
            "2996 < id                    | 2997, 2998, 2999",
            // n is null on every 1000th row
            "n is null                    | 0, 1000, 2000",
            "n is null and id in (0, 1, 1000) | 0, 1000"})
    void jniWriter_translatedFilters_returnExactlyTheMatchingRows(String where, String expected, @TempDir Path tmp)
            throws Exception {
        // Given
        Path file = tmp.resolve("jni_filters.vortex");
        writeIdsWithJni(file, BATCHES, BATCH_ROWS);

        // When
        List<Long> result = new java.util.ArrayList<>();
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select id from vtx.t where " + where + " order by id")) {
            while (rs.next()) {
                result.add(rs.getLong(1));
            }
        }

        // Then
        assertThat(result).containsExactlyElementsOf(
                java.util.Arrays.stream(expected.split(",")).map(String::trim).map(Long::valueOf).toList());
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

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // g = id % 7 has 7 distinct values over 3000 rows
            "select count(distinct g) from vtx.t                     | 7",
            "select count(distinct g) from vtx.t where id < 3        | 3",
            "select count(*) filter (where id < 10) from vtx.t       | 10",
            "select count(n) filter (where id < 1001) from vtx.t     | 999",
            "select min(id) filter (where id > 2000) from vtx.t      | 2001",
            "select max(id) filter (where id < 5) from vtx.t         | 4",
            "select sum(id) filter (where id < 4) from vtx.t         | 6"})
    void jniWriter_distinctAndFilteredAggregates_answerOverTheSelectedRows(String sql, long expected,
            @TempDir Path tmp) throws Exception {
        // Given — the statistics push-down answered COUNT/MIN/MAX/SUM from whole zones without
        // looking at DISTINCT or an aggregate's FILTER clause, so COUNT(DISTINCT g) returned the
        // row count and a FILTERed aggregate ignored its filter
        Path file = tmp.resolve("jni_distinct.vortex");
        writeIdsWithJni(file, BATCHES, BATCH_ROWS);

        // When
        long result;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            result = rs.getLong(1);
        }

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void jniWriter_minMaxOfStringsOutsideLatin1_answer(@TempDir Path tmp) throws Exception {
        // Given — Calcite types VARCHAR as ISO-8859-1, so a pushed-down literal holding 'Ω' failed
        // the query; the answer must be the strings Rust wrote, however it is computed
        Path file = tmp.resolve("jni_greek.vortex");
        Schema schema = new Schema(List.of(Field.notNullable("s", new ArrowType.Utf8())));
        String[] values = {"MSFT", "\u03A9mega", "AAPL"};
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            org.apache.arrow.vector.VarCharVector s = (org.apache.arrow.vector.VarCharVector) root.getVector("s");
            s.allocateNew(values.length);
            for (int i = 0; i < values.length; i++) {
                s.setSafe(i, values[i].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            root.setRowCount(values.length);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }

        // When
        String min;
        String max;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select min(s), max(s) from vtx.t")) {
            rs.next();
            min = rs.getString(1);
            max = rs.getString(2);
        }

        // Then
        assertThat(min).isEqualTo("AAPL");
        assertThat(max).isEqualTo("\u03A9mega");
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " where id < 1234", " where x > 0.5 and id >= 100"})
    void jniWriter_groupByDictionaryString_foldsEveryGroupLikeTheRows(String where, @TempDir Path tmp)
            throws Exception {
        // Given — Rust dict-encodes a low-cardinality string column with nulls. GROUP BY on it folds
        // per dictionary code into a Values row per group; every group's aggregates must equal the
        // ones computed from the rows written
        assertGroupBy(where, new String[]{"AAPL", "MSFT", "NVDA", "caf\u00E9", "AMZN", "META", "TSLA", null},
                true, tmp);
    }

    @Test
    void jniWriter_groupByStringOutsideLatin1_answersThroughTheScan(@TempDir Path tmp) throws Exception {
        // Given — a group key Latin-1 cannot hold has no VARCHAR literal, so the fold leaves the query
        // to the scan; the answer must not change
        assertGroupBy("", new String[]{"AAPL", "\u03A9mega", null}, false, tmp);
    }

    private static void assertGroupBy(String where, String[] symbols, boolean folded, Path tmp) throws Exception {
        Path file = tmp.resolve("jni_groups.vortex");
        List<Object[]> rows = writeGroupsWithJni(file, symbols);
        java.util.function.Predicate<Object[]> selected = switch (where) {
            case "" -> r -> true;
            case " where id < 1234" -> r -> (long) r[1] < 1234;
            default -> r -> (double) r[3] > 0.5 && (long) r[1] >= 100;
        };
        Map<String, List<Object[]>> byGroup = new java.util.HashMap<>();
        rows.stream().filter(selected).forEach(r -> byGroup.computeIfAbsent((String) r[0], k -> new java.util.ArrayList<>()).add(r));
        Map<String, String> expected = new java.util.HashMap<>();
        byGroup.forEach((group, groupRows) -> {
            List<Long> ns = groupRows.stream().map(r -> (Long) r[2]).filter(java.util.Objects::nonNull).toList();
            expected.put(group, groupRows.size() + "|" + ns.size()
                    + "|" + groupRows.stream().mapToLong(r -> (long) r[1]).sum()
                    + "|" + groupRows.stream().mapToLong(r -> (long) r[1]).min().orElseThrow()
                    + "|" + (ns.isEmpty() ? null : java.util.Collections.max(ns))
                    + "|" + groupRows.stream().mapToDouble(r -> (double) r[3]).max().orElseThrow());
        });
        String sql = "select s, count(*), count(n), sum(id), min(id), max(n), max(x) from vtx.t" + where + " group by s";

        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            // When
            String plan;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("explain plan for " + sql)) {
                rs.next();
                plan = rs.getString(1);
            }
            Map<String, String> result = new java.util.HashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getLong(2) + "|" + rs.getLong(3) + "|" + rs.getLong(4)
                            + "|" + rs.getLong(5) + "|" + rs.getObject(6) + "|" + rs.getDouble(7));
                }
            }

            // Then — equal to the rows, and folded rather than scanned when the keys allow it
            assertThat(result).isEqualTo(expected);
            if (folded) {
                assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan");
            }
        }
    }

    /// Writes `s` drawn from `symbols`, `id`, `n` (null every 5th row, and always null for the first
    /// symbol so its MAX(n) is SQL NULL) and `x`; returns the rows written.
    private static List<Object[]> writeGroupsWithJni(Path file, String[] symbols) throws Exception {
        Schema schema = new Schema(List.of(
                Field.nullable("s", new ArrowType.Utf8()),
                Field.notNullable("id", new ArrowType.Int(64, true)),
                Field.nullable("n", new ArrowType.Int(64, true)),
                Field.notNullable("x", new ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE))));
        List<Object[]> rows = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random(7);
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    org.apache.arrow.vector.VarCharVector s = (org.apache.arrow.vector.VarCharVector) root.getVector("s");
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    BigIntVector n = (BigIntVector) root.getVector("n");
                    org.apache.arrow.vector.Float8Vector x = (org.apache.arrow.vector.Float8Vector) root.getVector("x");
                    s.allocateNew(BATCH_ROWS);
                    ids.allocateNew(BATCH_ROWS);
                    n.allocateNew(BATCH_ROWS);
                    x.allocateNew(BATCH_ROWS);
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        long id = (long) batch * BATCH_ROWS + i;
                        String symbol = symbols[random.nextInt(symbols.length)];
                        Long nValue = id % 5 == 0 || symbols[0].equals(symbol) ? null : id * 3;
                        double xValue = random.nextDouble();
                        if (symbol == null) {
                            s.setNull(i);
                        } else {
                            s.setSafe(i, symbol.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                        ids.set(i, id);
                        if (nValue == null) {
                            n.setNull(i);
                        } else {
                            n.set(i, nValue);
                        }
                        x.set(i, xValue);
                        rows.add(new Object[]{symbol, id, nValue, xValue});
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
        return rows;
    }

    @ParameterizedTest(name = "where {0}")
    @CsvSource(delimiter = '|', value = {
            "i16 < 5                        | 2",
            // 200 is past the signed byte range: UInt8 must zero-extend, not read as -56
            "u8 > 100                       | 1",
            "i8 < 0                         | 1",
            "i16 = 5 and i8 = 9 and u8 = 3  | 1"})
    void jniWriter_whereOnNarrowIntegers_selectsTheMatchingRows(String where, long expected, @TempDir Path tmp)
            throws Exception {
        // Given — SMALLINT and TINYINT values reached Calcite's generated code as Integers, so any
        // WHERE on an Int16, UInt8 or Int8 column failed with a ClassCastException
        Path file = tmp.resolve("jni_narrow.vortex");
        Schema schema = new Schema(List.of(
                Field.notNullable("i16", new ArrowType.Int(16, true)),
                Field.notNullable("u8", new ArrowType.Int(8, false)),
                Field.notNullable("i8", new ArrowType.Int(8, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            org.apache.arrow.vector.SmallIntVector i16 = (org.apache.arrow.vector.SmallIntVector) root.getVector("i16");
            org.apache.arrow.vector.UInt1Vector u8 = (org.apache.arrow.vector.UInt1Vector) root.getVector("u8");
            org.apache.arrow.vector.TinyIntVector i8 = (org.apache.arrow.vector.TinyIntVector) root.getVector("i8");
            short[] i16Values = {-300, 5, 2};
            int[] u8Values = {200, 3, 7};
            byte[] i8Values = {-5, 9, 1};
            i16.allocateNew(3);
            u8.allocateNew(3);
            i8.allocateNew(3);
            for (int i = 0; i < 3; i++) {
                i16.set(i, i16Values[i]);
                u8.set(i, u8Values[i]);
                i8.set(i, i8Values[i]);
            }
            root.setRowCount(3);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }

        // When
        long result;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from vtx.t where " + where)) {
            rs.next();
            result = rs.getLong(1);
        }

        // Then
        assertThat(result).isEqualTo(expected);
    }

    private static void writeIdsWithJni(Path file, int batches, int batchRows) throws Exception {
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                Field.nullable("n", new ArrowType.Int(64, true)),
                Field.notNullable("g", new ArrowType.Int(64, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < batches; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    BigIntVector n = (BigIntVector) root.getVector("n");
                    BigIntVector g = (BigIntVector) root.getVector("g");
                    ids.allocateNew(batchRows);
                    n.allocateNew(batchRows);
                    g.allocateNew(batchRows);
                    for (int i = 0; i < batchRows; i++) {
                        long id = (long) batch * batchRows + i;
                        ids.set(i, id);
                        g.set(i, id % 7);
                        if (id % 1000 == 0) {
                            n.setNull(i);
                        } else {
                            n.set(i, id);
                        }
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
