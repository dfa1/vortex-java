package io.github.dfa1.vortex.integration;

import dev.vortex.api.Expression;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanLimit;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A filtered scan returns exactly the rows that match, and a limit counts those rows, as Rust's
/// `ScanBuilder` does ("maximal number of rows to read after filtering"). Java's filter only pruned
/// zones: the scan returned every row of the chunks it kept, and a limit cut those rows, matching
/// or not, so `withFilter(qty >= 25).withLimit(10)` returned rows Rust leaves out.
class ScanFilterLimitInteropIntegrationTest {

    /// Rows per batch: more than a zone (8192), so filters cross chunk and zone boundaries.
    private static final int ROWS = 10_000;
    private static final int BATCHES = 3;
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName QTY = ColumnName.of("qty");
    private static final ColumnName NAME = ColumnName.of("name");

    @TempDir
    static Path tmp;
    private static Path file;

    static {
        NativeLoader.loadJni();
    }

    @BeforeAll
    static void writeFile() throws IOException {
        // id ascending, qty = id % 50 with every 7th row null, name "n<id>"
        file = tmp.resolve("filter-limit.vtx");
        DType.Struct schema = DType.structBuilder()
                .field(ID, DType.I64).field(QTY, DType.I64.asNullable()).field(NAME, DType.UTF8).build();
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            for (int b = 0; b < BATCHES; b++) {
                long[] ids = new long[ROWS];
                Long[] qty = new Long[ROWS];
                String[] names = new String[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    long id = (long) b * ROWS + i;
                    ids[i] = id;
                    qty[i] = id % 7 == 3 ? null : id % 50;
                    names[i] = "n" + id;
                }
                writer.writeChunk(chunk -> chunk.put(ID, ids).put(QTY, qty).put(NAME, names));
            }
        }
    }

    record Case(String name, ScanFilter javaFilter, Expression jniFilter, Long limit) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        ScanFilter qtyAtLeast25 = ScanFilter.gte(QTY, 25L);
        Expression jniQtyAtLeast25 = Expression.binary(Expression.BinaryOp.GTE, Expression.column("qty"), Expression.literal(25L));
        ScanFilter idAndQty = ScanFilter.gte(ID, 12_000L).and(ScanFilter.lt(QTY, 5L));
        Expression jniIdAndQty = Expression.binary(Expression.BinaryOp.AND,
                Expression.binary(Expression.BinaryOp.GTE, Expression.column("id"), Expression.literal(12_000L)),
                Expression.binary(Expression.BinaryOp.LT, Expression.column("qty"), Expression.literal(5L)));
        ScanFilter none = ScanFilter.gt(QTY, 1_000L);
        Expression jniNone = Expression.binary(Expression.BinaryOp.GT, Expression.column("qty"), Expression.literal(1_000L));
        return Stream.of(
                new Case("qty >= 25", qtyAtLeast25, jniQtyAtLeast25, null),
                new Case("qty >= 25 limit 10", qtyAtLeast25, jniQtyAtLeast25, 10L),
                new Case("qty >= 25 limit 15000, across chunks", qtyAtLeast25, jniQtyAtLeast25, 15_000L),
                new Case("id >= 12000 and qty < 5 limit 100", idAndQty, jniIdAndQty, 100L),
                new Case("no row matches", none, jniNone, 10L),
                new Case("limit 0", qtyAtLeast25, jniQtyAtLeast25, 0L)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void javaScanReturnsTheRowsVortexJniReturns(Case c) throws IOException {
        // When
        List<List<Object>> result = readJava(c, List.of(ID, QTY, NAME));
        List<List<Object>> expected = readJni(c, new String[]{"id", "qty", "name"});

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aFilterOnAColumnNotProjectedStillSelectsRows(Case c) throws IOException {
        // Given: only name is projected, so the scan decodes qty and id to test rows, then drops them
        // When
        List<List<Object>> result = readJava(c, List.of(NAME));
        List<List<Object>> expected = readJni(c, new String[]{"name"});

        // Then
        assertThat(result).isEqualTo(expected);
    }

    private static List<List<Object>> readJava(Case c, List<ColumnName> columns) throws IOException {
        ScanOptions options = ScanOptions.columns(columns.toArray(ColumnName[]::new)).withFilter(c.javaFilter());
        if (c.limit() != null) {
            options = options.withLimit(ScanLimit.of(c.limit()));
        }
        List<List<Object>> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(options)) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    assertThat(chunk.columns().keySet()).containsExactlyElementsOf(columns);
                    assertThat(chunk.rowCount()).as("no empty chunk, as Rust returns no empty batch").isPositive();
                    for (long i = 0; i < chunk.rowCount(); i++) {
                        List<Object> row = new ArrayList<>();
                        for (ColumnName column : columns) {
                            row.add(value(chunk.column(column), i));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private static Object value(Array array, long i) {
        if (array instanceof MaskedArray masked && !masked.isValid(i)) {
            return null;
        }
        Array inner = MaskedArray.innerOrSelf(array);
        if (inner instanceof VarBinArray strings) {
            return strings.getString(i);
        }
        return ((LongArray) inner).getLong(i);
    }

    /// vortex-jni's filtered rows in file order, cut to the limit. The bundled vortex-jni refuses a
    /// scan with both a filter and a limit ("Vortex doesn't support scans with both a filter and a
    /// limit"); Rust's current `ScanBuilder` takes them, an ordered scan returning the earliest
    /// matching rows, which is this cut.
    private static List<List<Object>> readJni(Case c, String[] columns) throws IOException {
        var options = dev.vortex.api.ScanOptions.builder()
                .projection(Expression.select(columns, Expression.root()))
                .filter(c.jniFilter())
                .ordered(true);
        List<List<Object>> rows = new ArrayList<>();
        JniFiles.forEachBatch(file.toAbsolutePath().toUri().toString(), options.build(), root -> {
            for (int i = 0; i < root.getRowCount(); i++) {
                List<Object> row = new ArrayList<>();
                for (String column : columns) {
                    FieldVector vector = root.getVector(column);
                    Object value = vector.isNull(i) ? null : vector.getObject(i);
                    if (value instanceof Text text) {
                        value = text.toString();
                    }
                    row.add(value);
                }
                rows.add(row);
            }
        });
        if (c.limit() != null && rows.size() > c.limit()) {
            return rows.subList(0, c.limit().intValue());
        }
        return rows;
    }
}
