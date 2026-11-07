package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.DateTimePartsData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A file written without compression (`WriteOptions.cascading(0)`) reads back in vortex-jni for the
/// column shapes a Parquet import hands the writer. A nullable bool or struct failed in
/// `vortex.masked` ("no inner encoding"), and a timestamp handed over as [DateTimePartsData] reached
/// `vortex.primitive` as a record ("Argument is not an array"): each wrote no file at all.
class UncompressedWriteInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    /// Two batches, so the file has more than one chunk and the row range crosses them.
    private static final int ROWS = 5_000;
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName V = ColumnName.of("v");

    static {
        NativeLoader.loadJni();
    }

    /// A column shape: its dtype, the carrier for rows `[from, from + ROWS)`, and the value
    /// vortex-jni must return for each row, in the form [#jniValue] reduces Arrow's objects to.
    record Shape(DType dtype, IntFunction<Object> batch, IntFunction<Object> expected) {
    }

    static Stream<Arguments> shapes() {
        DType.Struct point = new DType.Struct(List.of(ColumnName.of("a")),
                List.of(new DType.Primitive(PType.F64, true)), true);
        return Stream.of(
                // every 7th row null: Boolean[] becomes NullableData over boolean[]
                Arguments.of(Named.of("nullable bool", new Shape(DTypes.BOOL.withNullable(true),
                        from -> boxedBools(from),
                        row -> row % 7 == 3 ? null : row % 2 == 0))),
                // every 5th struct null, every 3rd field value null inside a valid struct
                Arguments.of(Named.of("nullable struct", new Shape(point,
                        from -> structs(from),
                        row -> row % 5 == 1 ? "NULL" : String.valueOf(row % 3 == 0 ? null : row * 0.5)))),
                // as the Parquet importer hands over a timestamp column without nulls
                Arguments.of(Named.of("timestamp as datetimeparts", new Shape(
                        TimestampDtype.of(TimeUnit.Microseconds, null, true),
                        from -> new DateTimePartsData(timestamps(from), true),
                        row -> timestamp(row)))),
                // and one with nulls, which it hands over as NullableData over long[]
                Arguments.of(Named.of("nullable timestamp with nulls", new Shape(
                        TimestampDtype.of(TimeUnit.Microseconds, null, true),
                        from -> new NullableData(timestamps(from), validity(from)),
                        row -> row % 11 == 4 ? null : timestamp(row)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void jniReadsAnUncompressedJavaWrite(Shape shape, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("uncompressed.vortex");
        DType.Struct schema = DType.structBuilder().field(ID, DTypes.I64).field(V, shape.dtype()).build();
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(channel, schema, WriteOptions.cascading(0))) {
            for (int from = 0; from < 2 * ROWS; from += ROWS) {
                sut.writeChunk(Map.of(ID, LongStream.range(from, from + ROWS).toArray(), V, shape.batch().apply(from)));
            }
        }

        // When
        List<Object> full = readJni(file, null);
        List<Object> filtered = readJni(file, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column(ID.value()), Expression.literal((long) ROWS + 1_234)));

        // Then (vortex-jni may return partitions in a different order)
        assertThat(full).containsExactlyInAnyOrderElementsOf(expected(shape, 0, 2 * ROWS));
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(expected(shape, ROWS + 1_234, 2 * ROWS));
    }

    private static List<Object> expected(Shape shape, int from, int to) {
        List<Object> values = new ArrayList<>();
        for (int row = from; row < to; row++) {
            values.add(shape.expected().apply(row));
        }
        return values;
    }

    private static Boolean[] boxedBools(int from) {
        Boolean[] values = new Boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            int row = from + i;
            values[i] = row % 7 == 3 ? null : row % 2 == 0;
        }
        return values;
    }

    private static NullableData structs(int from) {
        double[] a = new double[ROWS];
        boolean[] aValid = new boolean[ROWS];
        boolean[] valid = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            int row = from + i;
            aValid[i] = row % 3 != 0;
            a[i] = aValid[i] ? row * 0.5 : 0;
            valid[i] = row % 5 != 1;
        }
        return new NullableData(new StructData(List.of(new NullableData(a, aValid))), valid);
    }

    private static long timestamp(int row) {
        // whole seconds from 2024-01-01 plus a sub-second part, so every datetimeparts part is used
        return 1_704_067_200_000_000L + row * 1_000_123L;
    }

    private static long[] timestamps(int from) {
        long[] values = new long[ROWS];
        Arrays.setAll(values, i -> timestamp(from + i));
        return values;
    }

    private static boolean[] validity(int from) {
        boolean[] valid = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            valid[i] = (from + i) % 11 != 4;
        }
        return valid;
    }

    /// Column `v` as vortex-jni returns it, each Arrow value reduced to a comparable form: a bool as
    /// itself, a timestamp as its raw long, a struct as its field `a` or `"NULL"` for a null struct.
    private static List<Object> readJni(Path file, Expression filter) throws IOException {
        var options = ScanOptions.builder()
                .projection(Expression.select(new String[]{V.value()}, Expression.root()));
        if (filter != null) {
            options.filter(filter);
        }
        List<Object> values = new ArrayList<>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(options.build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vector = root.getVector(V.value());
                    for (int i = 0; i < root.getRowCount(); i++) {
                        values.add(jniValue(vector, i));
                    }
                }
            }
        }
        return values;
    }

    private static Object jniValue(FieldVector vector, int i) {
        if (vector instanceof StructVector struct) {
            if (struct.isNull(i)) {
                return "NULL";
            }
            return String.valueOf(struct.getChild("a").getObject(i));
        }
        if (vector.isNull(i)) {
            return null;
        }
        if (vector instanceof TimeStampVector ts) {
            return ts.get(i);
        }
        return vector.getObject(i);
    }
}
