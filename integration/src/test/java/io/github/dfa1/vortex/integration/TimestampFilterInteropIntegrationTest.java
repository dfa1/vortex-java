package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.DateTimePartsData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A filter on a `vortex.datetimeparts` timestamp column selects the rows vortex-jni selects. The
/// comparison reassembles the parts once per chunk instead of per row; this checks the rows it
/// keeps, chunks whose min/max span the filter without matching (out-of-order outliers, as real
/// trip data has) included.
class TimestampFilterInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    /// Rows per batch: more than a zone (8192), so filters cross chunk and zone boundaries.
    private static final int ROWS = 10_000;
    private static final int BATCHES = 3;
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName TS = ColumnName.of("ts");
    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");
    private static final long MICROS_PER_SECOND = 1_000_000L;

    @TempDir
    static Path tmp;
    private static Path file;

    static {
        NativeLoader.loadJni();
    }

    @BeforeAll
    static void writeFile() throws IOException {
        // ts roughly ascending in whole seconds (one row every ~90 s, jittered), as trip records
        // are, and every 2,500th row an outlier from 2009, so most chunks' min/max span any window.
        // Written as the Parquet importer writes a timestamp column: a nullable column handed over
        // as DateTimePartsData, the only input the writer stores as datetimeparts (which has no
        // null rows on this path; the unit tests cover nulls).
        file = tmp.resolve("timestamp-filter.vtx");
        DType.Struct schema = DType.structBuilder()
                .field(ID, DType.I64)
                .field(TS, TimestampDtype.of(TimeUnit.Microseconds, null, true))
                .build();
        var rng = new Random(2024);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            for (int b = 0; b < BATCHES; b++) {
                long[] ids = new long[ROWS];
                long[] ts = new long[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    long id = (long) b * ROWS + i;
                    ids[i] = id;
                    if (id % 2_500 == 7) {
                        ts[i] = micros(Instant.parse("2009-01-01T00:00:00Z").plusSeconds(id));
                    } else {
                        ts[i] = micros(START.plusSeconds(id * 90 + rng.nextInt(600)));
                    }
                }
                writer.writeChunk(chunk -> chunk.put(ID, ids).put(TS, new DateTimePartsData(ts, true)));
            }
        }
    }

    record Case(String name, ScanFilter javaFilter, Expression jniFilter) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        long from = micros(START.plusSeconds(1_000_000));
        long to = micros(START.plusSeconds(1_003_600));
        long exact = exactTimestamp();
        return Stream.of(
                new Case("one-hour window", ScanFilter.and(ScanFilter.gte(TS, from), ScanFilter.lt(TS, to)),
                        Expression.and(binary(Expression.BinaryOp.GTE, from), binary(Expression.BinaryOp.LT, to))),
                new Case("eq an existing timestamp", ScanFilter.eq(TS, exact), binary(Expression.BinaryOp.EQ, exact)),
                new Case("neq an existing timestamp", ScanFilter.neq(TS, exact), binary(Expression.BinaryOp.NOT_EQ, exact)),
                new Case("before 2024 (the outliers)", ScanFilter.lt(TS, micros(START)), binary(Expression.BinaryOp.LT, micros(START))),
                new Case("gt the last day", ScanFilter.gt(TS, micros(START.plusSeconds(2_690_000))),
                        binary(Expression.BinaryOp.GT, micros(START.plusSeconds(2_690_000)))),
                new Case("no row matches", ScanFilter.gt(TS, micros(Instant.parse("2030-01-01T00:00:00Z"))),
                        binary(Expression.BinaryOp.GT, micros(Instant.parse("2030-01-01T00:00:00Z")))));
    }

    @Test
    void timestampColumnIsDateTimePartsEncoded() throws IOException {
        // Given the file written above
        // When
        try (VortexReader reader = VortexReader.open(file)) {
            // Then the filter runs over the encoding the change targets
            assertThat(reader.footer().arraySpecs()).contains(EncodingId.VORTEX_DATETIMEPARTS);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void javaFilteredScanReturnsTheRowsVortexJniReturns(Case c) throws IOException {
        // When
        List<List<Long>> result = readJava(c.javaFilter());
        List<List<Long>> expected = readJni(c.jniFilter(), 0, (long) ROWS * BATCHES);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void filteredRowRange_matchesTheJavaRowsInThatRange(Case c) throws IOException {
        // Given a row range crossing the first chunk boundary and several zones
        long begin = 7_777;
        long end = 23_333;

        // When
        List<List<Long>> result = readJava(c.javaFilter()).stream()
                .filter(row -> row.getFirst() >= begin && row.getFirst() < end).toList();
        List<List<Long>> expected = readJni(c.jniFilter(), begin, end);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    private static Expression binary(Expression.BinaryOp op, long micros) {
        return Expression.binary(op, Expression.column("ts"),
                Expression.literalTimestamp(micros, Expression.TimeUnit.MICROSECONDS, null));
    }

    private static long micros(Instant instant) {
        return Math.multiplyExact(instant.getEpochSecond(), MICROS_PER_SECOND) + instant.getNano() / 1_000;
    }

    /// The timestamp at row 12,345 as written, so `eq` has exactly one match to find.
    private static long exactTimestamp() {
        try {
            for (List<Long> row : readJava(ScanFilter.isNotNull(TS))) {
                if (row.getFirst() == 12_345L) {
                    return row.get(1);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("row 12345 is null");
    }

    /// `[id, ts]` per selected row, `ts` in microseconds or `null`, in file order.
    private static List<List<Long>> readJava(ScanFilter filter) throws IOException {
        List<List<Long>> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(ScanOptions.columns(ID, TS).withFilter(filter))) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    LongArray ids = chunk.column(ID);
                    Array ts = chunk.column(TS);
                    for (long i = 0; i < chunk.rowCount(); i++) {
                        Long value = null;
                        if (!(ts instanceof MaskedArray masked) || masked.isValid(i)) {
                            value = ((LongArray) MaskedArray.innerOrSelf(ts)).getLong(i);
                        }
                        List<Long> row = new ArrayList<>();
                        row.add(ids.getLong(i));
                        row.add(value);
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private static List<List<Long>> readJni(Expression filter, long begin, long end) throws IOException {
        var options = dev.vortex.api.ScanOptions.builder()
                .projection(Expression.select(new String[]{"id", "ts"}, Expression.root()))
                .filter(filter)
                .rowRangeBegin(begin)
                .rowRangeEnd(end)
                .ordered(true);
        List<List<Long>> rows = new ArrayList<>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(options.build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    TimeStampVector ts = (TimeStampVector) root.getVector("ts");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        List<Long> row = new ArrayList<>();
                        row.add(ids.get(i));
                        row.add(ts.isNull(i) ? null : ts.get(i));
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }
}
