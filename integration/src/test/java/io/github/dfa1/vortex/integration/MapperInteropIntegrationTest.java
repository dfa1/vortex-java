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
import io.github.dfa1.vortex.mapper.ColumnMapping;
import io.github.dfa1.vortex.mapper.Row;
import io.github.dfa1.vortex.mapper.Rows;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `mapper` module against vortex-jni, both ways: every [ColumnMapping] column kind written by
/// vortex-java and read back by vortex-jni (full, row-range and filtered) and by [Rows]; and a wide
/// file written by vortex-jni read through small projections, the module's reading model.
class MapperInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName VALUE = ColumnName.of("v");
    private static final int BATCHES = 3;
    private static final int BATCH_ROWS = 1_000;
    private static final int ROWS = BATCHES * BATCH_ROWS;
    private static final long THRESHOLD = 1_234;

    static {
        NativeLoader.loadJni();
    }

    /// Reads one cell of a [Row]; a method reference to a typed getter.
    @FunctionalInterface
    interface Getter {
        Object get(Row row, int column);
    }

    /// One [ColumnMapping.Builder] method each. Values cross the sign bit and, for the unsigned
    /// kinds, sit above the signed range, so a sign-extension or signed/unsigned mix-up reads back
    /// different; nullable kinds null every seventh row.
    enum Kind {
        BOOL(false, r -> r % 3 != 0, (b, v) -> b.bool("v", r -> (Boolean) v.apply(r)), Row::getBoolean),
        NULLABLE_BOOL(true, r -> r % 3 != 0, (b, v) -> b.nullableBool("v", r -> (Boolean) v.apply(r)), Row::getBoolean),
        I8(false, r -> (byte) (r * 7), (b, v) -> b.i8("v", r -> (Byte) v.apply(r)), Row::getByte),
        NULLABLE_I8(true, r -> (byte) (r * 7), (b, v) -> b.nullableI8("v", r -> (Byte) v.apply(r)), Row::getByte),
        U8(false, r -> (byte) (r * 7), (b, v) -> b.u8("v", r -> (Byte) v.apply(r)), Row::getByte),
        NULLABLE_U8(true, r -> (byte) (r * 7), (b, v) -> b.nullableU8("v", r -> (Byte) v.apply(r)), Row::getByte),
        I16(false, r -> (short) (r * 31), (b, v) -> b.i16("v", r -> (Short) v.apply(r)), Row::getShort),
        NULLABLE_I16(true, r -> (short) (r * 31), (b, v) -> b.nullableI16("v", r -> (Short) v.apply(r)), Row::getShort),
        U16(false, r -> (short) (r * 31), (b, v) -> b.u16("v", r -> (Short) v.apply(r)), Row::getShort),
        NULLABLE_U16(true, r -> (short) (r * 31), (b, v) -> b.nullableU16("v", r -> (Short) v.apply(r)), Row::getShort),
        I32(false, r -> (int) (r * 1_000_003L), (b, v) -> b.i32("v", r -> (Integer) v.apply(r)), Row::getInt),
        NULLABLE_I32(true, r -> (int) (r * 1_000_003L), (b, v) -> b.nullableI32("v", r -> (Integer) v.apply(r)), Row::getInt),
        U32(false, r -> (int) (r * 1_000_003L), (b, v) -> b.u32("v", r -> (Integer) v.apply(r)), Row::getInt),
        NULLABLE_U32(true, r -> (int) (r * 1_000_003L), (b, v) -> b.nullableU32("v", r -> (Integer) v.apply(r)), Row::getInt),
        I64(false, r -> r * -3_000_000_019L, (b, v) -> b.i64("v", r -> (Long) v.apply(r)), Row::getLong),
        NULLABLE_I64(true, r -> r * -3_000_000_019L, (b, v) -> b.nullableI64("v", r -> (Long) v.apply(r)), Row::getLong),
        U64(false, r -> r * -3_000_000_019L, (b, v) -> b.u64("v", r -> (Long) v.apply(r)), Row::getLong),
        NULLABLE_U64(true, r -> r * -3_000_000_019L, (b, v) -> b.nullableU64("v", r -> (Long) v.apply(r)), Row::getLong),
        F32(false, r -> r * 0.25f - 100, (b, v) -> b.f32("v", r -> (Float) v.apply(r)), Row::getFloat),
        NULLABLE_F32(true, r -> r * 0.25f - 100, (b, v) -> b.nullableF32("v", r -> (Float) v.apply(r)), Row::getFloat),
        F64(false, r -> r * 0.5 - 100, (b, v) -> b.f64("v", r -> (Double) v.apply(r)), Row::getDouble),
        NULLABLE_F64(true, r -> r * 0.5 - 100, (b, v) -> b.nullableF64("v", r -> (Double) v.apply(r)), Row::getDouble),
        // empty and non-ASCII strings among the plain ones: a length or an encoding slip shows
        UTF8(false, Kind::string, (b, v) -> b.utf8("v", r -> (String) v.apply(r)), Row::getString),
        NULLABLE_UTF8(true, Kind::string, (b, v) -> b.nullableUtf8("v", r -> (String) v.apply(r)), Row::getString),
        BINARY(false, Kind::bytes, (b, v) -> b.binary("v", r -> (byte[]) v.apply(r)), Row::getBytes),
        NULLABLE_BINARY(true, Kind::bytes, (b, v) -> b.nullableBinary("v", r -> (byte[]) v.apply(r)), Row::getBytes);

        final boolean nullable;
        final LongFunction<Object> base;
        final BiFunction<ColumnMapping.Builder<Long>, LongFunction<Object>, ColumnMapping.Builder<Long>> declare;
        final Getter getter;

        Kind(boolean nullable, LongFunction<Object> base,
                BiFunction<ColumnMapping.Builder<Long>, LongFunction<Object>, ColumnMapping.Builder<Long>> declare,
                Getter getter) {
            this.nullable = nullable;
            this.base = base;
            this.declare = declare;
            this.getter = getter;
        }

        Object value(long row) {
            return nullable && row % 7 == 0 ? null : base.apply(row);
        }

        private static Object string(long row) {
            return row % 5 == 0 ? "" : "r" + row + (row % 11 == 0 ? "é€" : "");
        }

        private static Object bytes(long row) {
            return ((String) string(row)).getBytes(StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void mapperWrites_jniReads_fullRowRangeAndFiltered(Kind kind, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("mapper.vortex");
        writeWithMapper(file, kind);

        // When
        List<Object> full = readJni(file, 0, ROWS, null);
        List<Object> range = readJni(file, 1_500, 2_100, null);
        List<Object> filtered = readJni(file, 0, ROWS, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(THRESHOLD)));

        // Then
        assertThat(full).containsExactlyElementsOf(expected(kind, 0, ROWS));
        assertThat(range).containsExactlyElementsOf(expected(kind, 1_500, 2_100));
        assertThat(filtered).containsExactlyElementsOf(expected(kind, THRESHOLD, ROWS));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void mapperWrites_mapperReads(Kind kind, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("mapper.vortex");
        writeWithMapper(file, kind);

        // When
        List<Object> result;
        try (var reader = VortexReader.open(file);
             Stream<Object> values = Rows.stream(reader, io.github.dfa1.vortex.reader.ScanOptions.columns(VALUE),
                     row -> row.isNull(0) ? null : normalize(kind.getter.get(row, 0)))) {
            result = values.toList();
        }

        // Then
        assertThat(result).containsExactlyElementsOf(expected(kind, 0, ROWS));
    }

    // ── vortex-jni writes a wide file, the mapper reads projections of it ─────────────────────────

    record Trade(long ts, String symbol, double price, int volume, boolean buy, Double fee, byte[] orderId) {
    }

    record PriceOnly(double price) {
    }

    record Fill(Double fee, String symbol, long volume) {
    }

    @Test
    void jniWrites_mapperReadsOneColumnProjection(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("trades.vortex");
        List<Trade> trades = trades();
        writeTradesWithJni(file, trades);

        // When
        List<PriceOnly> result;
        try (var reader = VortexReader.open(file);
             Stream<PriceOnly> prices = Rows.stream(reader,
                     io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of("price")),
                     row -> new PriceOnly(row.getDouble(0)))) {
            result = prices.toList();
        }

        // Then
        assertThat(result).containsExactlyElementsOf(trades.stream().map(t -> new PriceOnly(t.price())).toList());
    }

    @Test
    void jniWrites_mapperReadsProjectionInItsOwnOrder(@TempDir Path tmp) throws IOException {
        // Given: the projection lists fee before symbol, the reverse of the file; the scan hands
        // columns back in file order, so a Row indexed by scan order would swap them
        Path file = tmp.resolve("trades.vortex");
        List<Trade> trades = trades();
        writeTradesWithJni(file, trades);

        // When
        List<Fill> result;
        try (var reader = VortexReader.open(file);
             Stream<Fill> fills = Rows.stream(reader,
                     io.github.dfa1.vortex.reader.ScanOptions.columns(
                             ColumnName.of("fee"), ColumnName.of("symbol"), ColumnName.of("volume")),
                     row -> new Fill(
                             row.isNull(0) ? null : row.getDouble(0),
                             row.getString(1),
                             Integer.toUnsignedLong(row.getInt(2))))) {
            result = fills.toList();
        }

        // Then
        assertThat(result).containsExactlyElementsOf(trades.stream()
                .map(t -> new Fill(t.fee(), t.symbol(), Integer.toUnsignedLong(t.volume()))).toList());
    }

    @Test
    void jniWrites_mapperReadsLimit(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("trades.vortex");
        List<Trade> trades = trades();
        writeTradesWithJni(file, trades);

        // When
        List<Long> result;
        try (var reader = VortexReader.open(file);
             Stream<Long> ts = Rows.stream(reader,
                     io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of("ts")).withLimit(1_001),
                     row -> row.getLong(0))) {
            result = ts.toList();
        }

        // Then
        assertThat(result).containsExactlyElementsOf(trades.stream().limit(1_001).map(Trade::ts).toList());
    }

    @Test
    void stream_rejectsColumnMissingFromFile(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("trades.vortex");
        writeTradesWithJni(file, trades());

        try (var reader = VortexReader.open(file)) {
            // When / Then
            assertThatThrownBy(() -> Rows.stream(reader,
                    io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of("nope")), row -> row))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("no column 'nope' in file");
        }
    }

    @Test
    void getter_rejectsColumnOfAnotherType(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("trades.vortex");
        writeTradesWithJni(file, trades());

        try (var reader = VortexReader.open(file);
             Stream<Long> prices = Rows.stream(reader,
                     io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of("price")),
                     row -> row.getLong(0))) {
            // When / Then
            assertThatThrownBy(prices::toList)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("column 0 'price' is ")
                    .hasMessageEndingWith(", which getLong cannot read");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static void writeWithMapper(Path file, Kind kind) throws IOException {
        ColumnMapping<Long> mapping = kind.declare.apply(
                ColumnMapping.<Long>builder().i64("id", Long::longValue), kind::value).build();
        try (var channel = FileChannel.open(file, CREATE, WRITE);
             var writer = VortexWriter.create(channel, mapping.schema(), WriteOptions.defaults())) {
            for (int batch = 0; batch < BATCHES; batch++) {
                List<Long> rows = new ArrayList<>(BATCH_ROWS);
                for (long row = (long) batch * BATCH_ROWS; row < (long) (batch + 1) * BATCH_ROWS; row++) {
                    rows.add(row);
                }
                mapping.write(writer, rows);
            }
        }
    }

    private static List<Object> expected(Kind kind, long begin, long end) {
        List<Object> out = new ArrayList<>();
        for (long row = begin; row < end; row++) {
            out.add(normalize(kind.value(row)));
        }
        return out;
    }

    /// One comparable form for both readers: Arrow hands strings as [Text] and `U16` as `char`;
    /// byte arrays compare by content.
    private static Object normalize(Object value) {
        return switch (value) {
            case Text text -> text.toString();
            case Character c -> (short) c.charValue();
            case byte[] bytes -> HexFormat.of().formatHex(bytes);
            case null, default -> value;
        };
    }

    private static List<Object> readJni(Path file, long begin, long end, Expression filter) throws IOException {
        var builder = ScanOptions.builder()
                .projection(Expression.select(new String[]{"v"}, Expression.root()))
                .rowRangeBegin(begin)
                .rowRangeEnd(end);
        if (filter != null) {
            builder = builder.filter(filter);
        }
        List<Object> rows = new ArrayList<>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(builder.build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vector = root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        rows.add(vector.isNull(i) ? null : normalize(vector.getObject(i)));
                    }
                }
            }
        }
        return rows;
    }

    /// Every column kind of the Trade example, with null fees, volumes above `Integer.MAX_VALUE`
    /// (raw bits read as negative ints) and empty and non-ASCII symbols.
    private static List<Trade> trades() {
        List<Trade> trades = new ArrayList<>(ROWS);
        for (int row = 0; row < ROWS; row++) {
            trades.add(new Trade(
                    1_700_000_000_000L + row * 1_000L,
                    row % 5 == 0 ? "" : "SYM" + row % 13 + (row % 11 == 0 ? "é€" : ""),
                    100 + row * 0.01,
                    row * 1_500_007,
                    row % 2 == 0,
                    row % 7 == 0 ? null : row * 0.001,
                    HexFormat.of().parseHex("%08x".formatted(row))));
        }
        return trades;
    }

    private static void writeTradesWithJni(Path file, List<Trade> trades) throws IOException {
        Schema schema = new Schema(List.of(
                Field.notNullable("ts", new ArrowType.Int(64, true)),
                Field.notNullable("symbol", ArrowType.Utf8.INSTANCE),
                Field.notNullable("price", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("volume", new ArrowType.Int(32, false)),
                Field.notNullable("buy", ArrowType.Bool.INSTANCE),
                Field.nullable("fee", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("order_id", ArrowType.Binary.INSTANCE)));
        try (var writer = dev.vortex.api.VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(),
                schema, ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    root.allocateNew();
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        Trade t = trades.get(batch * BATCH_ROWS + i);
                        ((BigIntVector) root.getVector("ts")).setSafe(i, t.ts());
                        ((VarCharVector) root.getVector("symbol")).setSafe(i, t.symbol().getBytes(StandardCharsets.UTF_8));
                        ((Float8Vector) root.getVector("price")).setSafe(i, t.price());
                        ((UInt4Vector) root.getVector("volume")).setSafe(i, t.volume());
                        ((BitVector) root.getVector("buy")).setSafe(i, t.buy() ? 1 : 0);
                        Float8Vector fee = (Float8Vector) root.getVector("fee");
                        if (t.fee() == null) {
                            fee.setNull(i);
                        } else {
                            fee.setSafe(i, t.fee());
                        }
                        ((VarBinaryVector) root.getVector("order_id")).setSafe(i, t.orderId());
                    }
                    root.setRowCount(BATCH_ROWS);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                         ArrowSchema exported = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, exported);
                        writer.writeBatch(array.memoryAddress(), exported.memoryAddress());
                    }
                }
            }
        }
    }
}
