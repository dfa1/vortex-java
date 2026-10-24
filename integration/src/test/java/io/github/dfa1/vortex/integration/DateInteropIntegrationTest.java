package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;

/// `vortex.date` written by vortex-jni, read by Java. Rust stores an Arrow `Date32` as
/// `vortex.date(Days)` over `I32` and a `Date64` as `vortex.date(Milliseconds)` over `I64`; the
/// Java decoder used to read every storage value as days, so a `Date64` column came back as dates
/// tens of thousands of years away. Both units are read through a full scan and a filter on a
/// sibling column, with nulls and dates before the epoch.
class DateInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName DATE = ColumnName.of("d");
    private static final int CHUNKS = 3;
    private static final int CHUNK_ROWS = 1_000;
    private static final int ROWS = CHUNKS * CHUNK_ROWS;
    private static final long MILLIS_PER_DAY = 86_400_000L;

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest
    @EnumSource(DateUnit.class)
    void rustWrites_javaReads_fullScan(DateUnit unit, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dates.vortex");
        writeWithJni(file, unit);

        // When
        SequencedMap<Long, LocalDate> result = readJava(file, ScanOptions.all());

        // Then
        assertThat(result).hasSize(ROWS);
        result.forEach((id, date) -> assertThat(date).as("row %d", id).isEqualTo(expected(id)));
    }

    @ParameterizedTest
    @EnumSource(DateUnit.class)
    void rustWrites_javaReads_filtered(DateUnit unit, @TempDir Path tmp) throws IOException {
        // Given — a zone-map filter keeps a superset of the matching rows; each date must still
        // sit next to its own id
        Path file = tmp.resolve("dates.vortex");
        writeWithJni(file, unit);
        long threshold = CHUNK_ROWS + CHUNK_ROWS / 2;

        // When
        SequencedMap<Long, LocalDate> result = readJava(file, ScanOptions.all().withFilter(ScanFilter.gte(ID, threshold)));

        // Then
        assertThat(result.keySet()).contains(threshold, (long) ROWS - 1);
        result.forEach((id, date) -> assertThat(date).as("row %d", id).isEqualTo(expected(id)));
    }

    @ParameterizedTest
    @EnumSource(DateUnit.class)
    void rustWrites_dtypeRecordsTheUnit(DateUnit unit, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dates.vortex");
        writeWithJni(file, unit);

        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            // When
            DType.Extension result = (DType.Extension) ((DType.Struct) reader.dtype()).fieldTypes().get(1);

            // Then — the unit byte and storage width Rust chose, which the decoder must honor
            TimeUnit expectedUnit = unit == DateUnit.DAY ? TimeUnit.Days : TimeUnit.Milliseconds;
            assertThat(TimeUnit.fromTag(result.metadata().get(ValueLayout.JAVA_BYTE, 0))).isEqualTo(expectedUnit);
            assertThat(result.storageDType()).isEqualTo(unit == DateUnit.DAY ? DType.I32.asNullable() : DType.I64.asNullable());
        }
    }

    @ParameterizedTest
    @EnumSource(DateUnit.class)
    void rustWrites_dtypeOutlivesTheReader(DateUnit unit, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dates.vortex");
        writeWithJni(file, unit);
        DType.Extension result;

        // When — the dtype is kept past close, as a SQL schema caches it
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = (DType.Extension) ((DType.Struct) reader.dtype()).fieldTypes().get(1);
        }

        // Then — the metadata was a slice of the closed file mapping, so this threw "Already closed"
        TimeUnit expectedUnit = unit == DateUnit.DAY ? TimeUnit.Days : TimeUnit.Milliseconds;
        assertThat(TimeUnit.fromTag(result.metadata().get(ValueLayout.JAVA_BYTE, 0))).isEqualTo(expectedUnit);
    }

    /// Every ninth row is null; the rest step 37 days from 1915, so a quarter of them precede the
    /// epoch, where flooring a millisecond count must not round toward zero.
    private static LocalDate expected(long row) {
        return row % 9 == 4 ? null : LocalDate.ofEpochDay(-20_000 + row * 37);
    }

    private static void writeWithJni(Path file, DateUnit unit) throws IOException {
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                new Field("d", FieldType.nullable(new ArrowType.Date(unit)), null)));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    ids.allocateNew(CHUNK_ROWS);
                    root.getVector("d").setInitialCapacity(CHUNK_ROWS);
                    root.getVector("d").allocateNew();
                    for (int i = 0; i < CHUNK_ROWS; i++) {
                        long row = (long) chunk * CHUNK_ROWS + i;
                        ids.setSafe(i, row);
                        LocalDate date = expected(row);
                        if (unit == DateUnit.DAY) {
                            DateDayVector days = (DateDayVector) root.getVector("d");
                            if (date == null) {
                                days.setNull(i);
                            } else {
                                days.setSafe(i, Math.toIntExact(date.toEpochDay()));
                            }
                        } else {
                            DateMilliVector millis = (DateMilliVector) root.getVector("d");
                            if (date == null) {
                                millis.setNull(i);
                            } else {
                                millis.setSafe(i, date.toEpochDay() * MILLIS_PER_DAY);
                            }
                        }
                    }
                    root.setRowCount(CHUNK_ROWS);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                         ArrowSchema exported = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, exported);
                        writer.writeBatch(array.memoryAddress(), exported.memoryAddress());
                    }
                }
            }
        }
    }

    private static SequencedMap<Long, LocalDate> readJava(Path file, ScanOptions options) throws IOException {
        SequencedMap<Long, LocalDate> out = new LinkedHashMap<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(options)) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    List<LocalDate> dates = chunk.as(DATE, LocalDate.class);
                    LongArray ids = (LongArray) chunk.column(ID);
                    for (int i = 0; i < dates.size(); i++) {
                        out.put(ids.getLong(i), dates.get(i));
                    }
                }
            }
        }
        return out;
    }
}
