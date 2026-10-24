package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.RowSelection;
import io.github.dfa1.vortex.writer.encode.DateExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.TimeExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// An all-null chunk is written as one null-scalar `vortex.constant` (Rust's compressor does so
/// for any all-invalid array) and read back as a masked array with every row null. Every column
/// type the writer takes goes through the public API in three chunk shapes, next to a chunk with
/// values or not, and is checked row by row and through the zone-map-pruned scans that read the
/// null count and the bounds of those chunks.
class AllNullColumnRoundTripTest {

    /// More rows per batch than a zone (8192), so each batch keeps chunks of its own null shape.
    private static final int ROWS = 10_000;
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName COLUMN = ColumnName.of("v");

    @TempDir
    Path tmp;

    /// Which batches hold values; the others are all null.
    enum Shape {
        ALL_NULL(false, false), NULL_THEN_VALUES(false, true), VALUES_THEN_NULL(true, false);

        final boolean[] valued;

        Shape(boolean first, boolean second) {
            this.valued = new boolean[]{first, second};
        }

        long nonNullRows() {
            long rows = 0;
            for (boolean v : valued) {
                if (v) {
                    rows += ROWS;
                }
            }
            return rows;
        }
    }

    /// A column type: its dtype, a batch of `ROWS` values (nulls when `valued` is false), and the
    /// lowest value written, for a comparison every valued row passes (`null` when the type has no
    /// comparison filter).
    record Column(String name, DType dtype, IntFunction<Object> batch, Comparable<?> lowest) {
        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("cases")
    void everyRowOfAnAllNullChunkReadsNull(Column column, Shape shape) throws IOException {
        // Given
        Path file = write(column, shape);

        // When
        List<Boolean> result = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(chunk -> {
                Array array = chunk.column(COLUMN);
                for (long i = 0; i < array.length(); i++) {
                    result.add(isNull(array, i));
                }
            });
        }

        // Then
        List<Boolean> expected = new ArrayList<>();
        for (boolean valued : shape.valued) {
            expected.addAll(java.util.Collections.nCopies(ROWS, !valued));
        }
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("cases")
    void nullScansPruneByTheNullCountOfAllNullChunks(Column column, Shape shape) throws IOException {
        // Given: a wrong null count on a null-constant chunk prunes it from IS NULL or keeps it in
        // IS NOT NULL
        Path file = write(column, shape);

        // When
        long nulls = countRows(file, ScanFilter.isNull(COLUMN));
        long nonNulls = countRows(file, ScanFilter.isNotNull(COLUMN));

        // Then
        assertThat(nulls).isEqualTo(2L * ROWS - shape.nonNullRows());
        assertThat(nonNulls).isEqualTo(shape.nonNullRows());
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("comparableCases")
    void aComparisonSkipsEveryRowOfAnAllNullChunk(Column column, Shape shape) throws IOException {
        // Given: every value passes the comparison, and no null does; an all-null chunk read with
        // its placeholder 0 or "" as a value would add rows, a wrong bound would drop valued ones
        Path file = write(column, shape);

        // When
        long result = countRows(file, ScanFilter.gte(COLUMN, column.lowest()));

        // Then
        assertThat(result).isEqualTo(shape.nonNullRows());
    }

    static Stream<Arguments> cases() {
        return columns().flatMap(c -> Arrays.stream(Shape.values()).map(s -> Arguments.of(c, s)));
    }

    static Stream<Arguments> comparableCases() {
        return columns().filter(c -> c.lowest() != null)
                .flatMap(c -> Arrays.stream(Shape.values()).map(s -> Arguments.of(c, s)));
    }

    static Stream<Column> columns() {
        // values start above the placeholder (0, false, "") so a placeholder read as a value shows
        return Stream.of(
                new Column("I8", DType.I8.asNullable(), v -> fill(new Byte[ROWS], v, i -> (byte) (1 + i % 100)), (byte) 1),
                new Column("U8", DType.U8.asNullable(), v -> fill(new Byte[ROWS], v, i -> (byte) (1 + i % 200)), (byte) 1),
                new Column("I16", DType.I16.asNullable(), v -> fill(new Short[ROWS], v, i -> (short) (1 + i)), (short) 1),
                new Column("U16", DType.U16.asNullable(), v -> fill(new Short[ROWS], v, i -> (short) (1 + i)), (short) 1),
                new Column("I32", DType.I32.asNullable(), v -> fill(new Integer[ROWS], v, i -> 1 + i), 1),
                new Column("U32", DType.U32.asNullable(), v -> fill(new Integer[ROWS], v, i -> 1 + i), 1),
                new Column("I64", DType.I64.asNullable(), v -> fill(new Long[ROWS], v, i -> 1L + i), 1L),
                new Column("U64", DType.U64.asNullable(), v -> fill(new Long[ROWS], v, i -> 1L + i), 1L),
                new Column("F32", DType.F32.asNullable(), v -> fill(new Float[ROWS], v, i -> 1.5f + i), 1.5f),
                new Column("F64", DType.F64.asNullable(), v -> fill(new Double[ROWS], v, i -> 1.5 + i), 1.5),
                new Column("Bool", DType.BOOL.asNullable(), v -> fill(new Boolean[ROWS], v, i -> true), true),
                new Column("Utf8", DType.UTF8.asNullable(), v -> fill(new String[ROWS], v, i -> "v" + i), "v"),
                new Column("Binary", DType.BINARY.asNullable(),
                        v -> fill(new byte[ROWS][], v, i -> new byte[]{1, (byte) i}), null),
                new Column("Decimal", new DType.Decimal((byte) 10, (byte) 2, true),
                        v -> fill(new BigDecimal[ROWS], v, i -> BigDecimal.valueOf(100 + i, 2)), BigDecimal.ONE),
                new Column("Date", DateExtensionEncoder.INSTANCE.dtype(true),
                        v -> list(v, i -> LocalDate.of(2020, 1, 1).plusDays(i % 1000)), null),
                new Column("Time", TimeExtensionEncoder.INSTANCE.dtype(true),
                        v -> list(v, i -> LocalTime.ofSecondOfDay(1 + i)), null),
                new Column("Timestamp", TimestampExtensionEncoder.INSTANCE.dtype(true),
                        v -> list(v, i -> Instant.ofEpochMilli(1_700_000_000_000L + i)), null),
                new Column("Uuid", UuidExtensionEncoder.INSTANCE.dtype(true),
                        v -> list(v, i -> new UUID(1, i)), null));
    }

    private Path write(Column column, Shape shape) throws IOException {
        Path file = tmp.resolve(column.name() + "-" + shape + ".vtx");
        DType.Struct schema = DType.structBuilder().field(ID, DType.I64).field(COLUMN, column.dtype()).build();
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            for (int batch = 0; batch < shape.valued.length; batch++) {
                long first = (long) batch * ROWS;
                long[] ids = new long[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    ids[i] = first + i;
                }
                Object values = column.batch().apply(shape.valued[batch] ? 1 : 0);
                writer.writeChunk(chunk -> chunk.put(ID, ids).put(COLUMN, values));
            }
        }
        return file;
    }

    /// The rows `filter` selects: the scan prunes zones by their stats, then each surviving chunk's
    /// rows are tested, so a zone pruned by mistake loses rows and a placeholder read as a value
    /// adds them.
    private static long countRows(Path file, ScanFilter filter) throws IOException {
        long[] rows = {0};
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(ScanOptions.all().withFilter(filter))) {
            iter.forEachRemaining(chunk -> {
                RowSelection selection = Compute.matcher(chunk, filter);
                for (long i = 0; i < chunk.rowCount(); i++) {
                    if (selection.test(i)) {
                        rows[0]++;
                    }
                }
            });
        }
        return rows[0];
    }

    private static boolean isNull(Array array, long i) {
        if (array instanceof NullArray) {
            return true;
        }
        return array instanceof MaskedArray masked && !masked.isValid(i);
    }

    /// `values` with every slot set by `value` when `valued` is 1, left null when 0.
    private static <T> T[] fill(T[] values, int valued, IntFunction<T> value) {
        if (valued == 1) {
            for (int i = 0; i < values.length; i++) {
                values[i] = value.apply(i);
            }
        }
        return values;
    }

    private static List<Object> list(int valued, IntFunction<Object> value) {
        List<Object> values = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            values.add(valued == 1 ? value.apply(i) : null);
        }
        return values;
    }
}
