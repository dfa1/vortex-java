package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.fbs.FbsArrayStats;
import io.github.dfa1.vortex.core.fbs.FbsFileStatistics;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimeUnitMetadata;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The file statistics in the postscript's `statistics` segment, against what vortex-jni writes for the
/// same batches (#664): one `ArrayStats` per top-level field holding Rust's `PRUNING_STATS` (min, max,
/// sum, null count, NaN count), each taken per batch and folded. The data hits every rule of that
/// fold: a string min cut to 64 bytes in one batch only (inexact min, exact max), a string column
/// with an all-null batch and a binary max that cannot be rounded up (both lose their max), an `i64`
/// sum that overflows (no sum), decimals of two widths (the sum is wider than the values), an
/// all-null float column, an extension (storage min/max, no sum) and a nullable struct (null count
/// only).
class FileStatisticsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 1000;
    private static final int BATCHES = 2;
    private static final DType.Extension DATE = new DType.Extension(ExtensionId.VORTEX_DATE, DTypes.I32,
            new TimeUnitMetadata(TimeUnit.Days), false);
    private static final List<ColumnName> NAMES = List.of("i64", "i32n", "f64nan", "f16", "f32", "u8", "u64", "ovf",
            "utf8", "utf8nb", "bin", "booln", "dec10", "dec5", "date", "f64null", "st").stream().map(ColumnName::of).toList();

    @BeforeAll
    static void loadJni() {
        NativeLoader.loadJni();
    }

    @Test
    void javaWritesTheFileStatisticsJniWrites(@TempDir Path tmp) throws Exception {
        // Given — the same two batches through both writers
        Path jni = tmp.resolve("jni.vortex");
        writeJni(jni);
        Path java = tmp.resolve("java.vortex");
        writeJava(java);

        // When
        List<String> result = fileStatistics(java);

        // Then — field by field, byte for byte: each scalar's kind and width, each precision
        assertThat(result).isEqualTo(fileStatistics(jni));
    }

    @Test
    void jniReadsAJavaFileWithFileStatistics(@TempDir Path tmp) throws Exception {
        // Given — Rust parses the statistics segment when it opens the file, checking every scalar
        // against its field's dtype: a wrong scalar kind makes the whole file unreadable
        Path java = tmp.resolve("java.vortex");
        writeJava(java);

        // When
        long result = jniRowCount(java);

        // Then
        assertThat(result).isEqualTo((long) ROWS * BATCHES);
    }

    @Test
    void nullColumn_countsEveryRowNull(@TempDir Path tmp) throws Exception {
        // Given — a Null column on its own: the default encoders have none for it. Every row is
        // null, though the carrier has no validity to say so
        Schema arrow = new Schema(List.of(Field.nullable("n", ArrowType.Null.INSTANCE)));
        Path jni = tmp.resolve("jni.vortex");
        try (dev.vortex.api.VortexWriter w = dev.vortex.api.VortexWriter.builder(SESSION,
                jni.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR);
             ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
            root.setRowCount(ROWS);
            Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, schema);
            w.writeBatch(array.memoryAddress(), schema.memoryAddress());
        }
        Path java = tmp.resolve("java.vortex");
        DType.Struct schema = new DType.Struct(List.of(ColumnName.of("n")), List.of(DTypes.NULL), false);
        try (FileChannel ch = FileChannel.open(java, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter w = VortexWriter.create(ch, schema, WriteOptions.defaults(), List.of(new NullEncodingEncoder()))) {
            w.writeChunk(Map.of(ColumnName.of("n"), new long[ROWS]));
        }

        // When
        List<String> result = fileStatistics(java);

        // Then
        assertThat(result).isEqualTo(fileStatistics(jni));
    }

    // ── data ────────────────────────────────────────────────────────────────

    private static boolean i32Valid(int row) {
        return row % 7 != 0;
    }

    private static double f64(int row) {
        return row % 11 == 0 ? Double.NaN : row * 0.5;
    }

    private static String utf8(int batch, int i) {
        // only batch 0 has a value past 64 bytes, and it is the column minimum
        return batch == 0 && i == 0 ? "a".repeat(80) : "m" + (batch * ROWS + i);
    }

    private static String utf8nb(int batch, int i) {
        return batch == 1 || i % 3 == 0 ? null : "v" + i;
    }

    private static byte[] bin(int batch, int i) {
        // batch 1's max is 70 0xFF bytes: no 64-byte upper bound exists
        if (batch == 1 && i == 5) {
            byte[] ff = new byte[70];
            Arrays.fill(ff, (byte) 0xFF);
            return ff;
        }
        return new byte[]{1, (byte) i};
    }

    private static Boolean bool(int row) {
        return row % 3 == 0 ? null : row % 2 == 0;
    }

    private static BigDecimal dec10(int row) {
        return row % 4 == 0 ? null : BigDecimal.valueOf(row * 101L, 2);
    }

    private static BigDecimal dec5(int row) {
        return BigDecimal.valueOf(row % 999 * 7L, 2);
    }

    private static boolean structValid(int row) {
        return row % 5 != 0;
    }

    // ── writers ─────────────────────────────────────────────────────────────

    private static void writeJni(Path file) throws Exception {
        Field struct = new Field("st", FieldType.nullable(ArrowType.Struct.INSTANCE),
                List.of(Field.nullable("a", new ArrowType.Int(32, true))));
        Schema arrow = new Schema(List.of(
                Field.notNullable("i64", new ArrowType.Int(64, true)),
                Field.nullable("i32n", new ArrowType.Int(32, true)),
                Field.notNullable("f64nan", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("f16", new ArrowType.FloatingPoint(FloatingPointPrecision.HALF)),
                Field.notNullable("f32", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                Field.notNullable("u8", new ArrowType.Int(8, false)),
                Field.notNullable("u64", new ArrowType.Int(64, false)),
                Field.notNullable("ovf", new ArrowType.Int(64, true)),
                Field.notNullable("utf8", ArrowType.Utf8.INSTANCE),
                Field.nullable("utf8nb", ArrowType.Utf8.INSTANCE),
                Field.notNullable("bin", ArrowType.Binary.INSTANCE),
                Field.nullable("booln", ArrowType.Bool.INSTANCE),
                Field.nullable("dec10", new ArrowType.Decimal(10, 2, 128)),
                Field.notNullable("dec5", new ArrowType.Decimal(5, 2, 128)),
                Field.notNullable("date", new ArrowType.Date(DateUnit.DAY)),
                Field.nullable("f64null", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                struct));
        try (dev.vortex.api.VortexWriter w = dev.vortex.api.VortexWriter.builder(SESSION,
                file.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
                    root.allocateNew();
                    fillArrow(root, batch);
                    root.setRowCount(ROWS);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, schema);
                        w.writeBatch(array.memoryAddress(), schema.memoryAddress());
                    }
                }
            }
        }
    }

    private static void fillArrow(VectorSchemaRoot root, int batch) {
        StructVector st = (StructVector) root.getVector("st");
        IntVector stA = (IntVector) st.getChild("a");
        for (int i = 0; i < ROWS; i++) {
            int row = batch * ROWS + i;
            ((BigIntVector) root.getVector("i64")).setSafe(i, row * 3L - 100);
            IntVector i32n = (IntVector) root.getVector("i32n");
            if (i32Valid(row)) {
                i32n.setSafe(i, row % 1000);
            } else {
                i32n.setNull(i);
            }
            ((Float8Vector) root.getVector("f64nan")).setSafe(i, f64(row));
            ((Float2Vector) root.getVector("f16")).setSafe(i, Float.floatToFloat16(row * 0.25f));
            ((Float4Vector) root.getVector("f32")).setSafe(i, row * 1.5f);
            ((UInt1Vector) root.getVector("u8")).setSafe(i, row % 200);
            ((UInt8Vector) root.getVector("u64")).setSafe(i, row * 7L);
            ((BigIntVector) root.getVector("ovf")).setSafe(i, Long.MAX_VALUE / 3);
            ((VarCharVector) root.getVector("utf8")).setSafe(i, utf8(batch, i).getBytes(StandardCharsets.UTF_8));
            VarCharVector utf8nb = (VarCharVector) root.getVector("utf8nb");
            if (utf8nb(batch, i) == null) {
                utf8nb.setNull(i);
            } else {
                utf8nb.setSafe(i, utf8nb(batch, i).getBytes(StandardCharsets.UTF_8));
            }
            ((VarBinaryVector) root.getVector("bin")).setSafe(i, bin(batch, i));
            BitVector booln = (BitVector) root.getVector("booln");
            if (bool(row) == null) {
                booln.setNull(i);
            } else {
                booln.setSafe(i, bool(row) ? 1 : 0);
            }
            DecimalVector dec10 = (DecimalVector) root.getVector("dec10");
            if (dec10(row) == null) {
                dec10.setNull(i);
            } else {
                dec10.setSafe(i, dec10(row));
            }
            ((DecimalVector) root.getVector("dec5")).setSafe(i, dec5(row));
            ((DateDayVector) root.getVector("date")).setSafe(i, 19000 + row);
            ((Float8Vector) root.getVector("f64null")).setNull(i);
            if (structValid(row)) {
                st.setIndexDefined(i);
                stA.setSafe(i, row);
            } else {
                st.setNull(i);
            }
        }
    }

    private static void writeJava(Path file) throws IOException {
        DType.Struct schema = new DType.Struct(NAMES, List.of(
                DTypes.I64, DTypes.I32.asNullable(), DTypes.F64, DTypes.F16, DTypes.F32, DTypes.U8, DTypes.U64, DTypes.I64,
                DTypes.UTF8, DTypes.UTF8.asNullable(), DTypes.BINARY, DTypes.BOOL.asNullable(),
                DType.Decimal.of(10, 2, true), DType.Decimal.of(5, 2, false), DATE, DTypes.F64.asNullable(),
                new DType.Struct(List.of(ColumnName.of("a")), List.of(DTypes.I32.asNullable()), true)), false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter w = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int batch = 0; batch < BATCHES; batch++) {
                w.writeChunk(javaBatch(batch));
            }
        }
    }

    private static Map<ColumnName, Object> javaBatch(int batch) {
        long[] i64 = new long[ROWS];
        Integer[] i32n = new Integer[ROWS];
        double[] f64nan = new double[ROWS];
        short[] f16 = new short[ROWS];
        float[] f32 = new float[ROWS];
        byte[] u8 = new byte[ROWS];
        long[] u64 = new long[ROWS];
        long[] ovf = new long[ROWS];
        String[] utf8 = new String[ROWS];
        String[] utf8nb = new String[ROWS];
        byte[][] bin = new byte[ROWS][];
        Boolean[] booln = new Boolean[ROWS];
        BigDecimal[] dec10 = new BigDecimal[ROWS];
        BigDecimal[] dec5 = new BigDecimal[ROWS];
        int[] date = new int[ROWS];
        Double[] f64null = new Double[ROWS];
        int[] stA = new int[ROWS];
        boolean[] stValid = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            int row = batch * ROWS + i;
            i64[i] = row * 3L - 100;
            i32n[i] = i32Valid(row) ? row % 1000 : null;
            f64nan[i] = f64(row);
            f16[i] = Float.floatToFloat16(row * 0.25f);
            f32[i] = row * 1.5f;
            u8[i] = (byte) (row % 200);
            u64[i] = row * 7L;
            ovf[i] = Long.MAX_VALUE / 3;
            utf8[i] = utf8(batch, i);
            utf8nb[i] = utf8nb(batch, i);
            bin[i] = bin(batch, i);
            booln[i] = bool(row);
            dec10[i] = dec10(row);
            dec5[i] = dec5(row);
            date[i] = 19000 + row;
            stValid[i] = structValid(row);
            stA[i] = stValid[i] ? row : 0;
        }
        List<Object> values = List.of(i64, i32n, f64nan, f16, f32, u8, u64, ovf, utf8, utf8nb, bin, booln, dec10, dec5,
                date, f64null, new NullableData(new StructData(List.of(new NullableData(stA, stValid))), stValid));
        Map<ColumnName, Object> chunk = new LinkedHashMap<>();
        for (int c = 0; c < NAMES.size(); c++) {
            chunk.put(NAMES.get(c), values.get(c));
        }
        return chunk;
    }

    // ── readers ─────────────────────────────────────────────────────────────

    /// Each field's `ArrayStats` table as written: every stat's presence, the raw scalar bytes and
    /// each bound's precision.
    private static List<String> fileStatistics(Path file) throws IOException {
        MemorySegment seg = MemorySegment.ofArray(Files.readAllBytes(file));
        long size = seg.byteSize();
        int psLen = Short.toUnsignedInt(seg.get(VortexFormat.LE_SHORT, size - VortexFormat.TRAILER_SIZE + 2));
        FbsPostscript ps = FbsPostscript.getRootAsFbsPostscript(seg.asSlice(size - VortexFormat.TRAILER_SIZE - psLen, psLen));
        FbsPostscriptSegment stats = ps.statistics();
        assertThat(stats).as("statistics segment").isNotNull();
        FbsFileStatistics fs = FbsFileStatistics.getRootAsFbsFileStatistics(seg.asSlice(stats.offset(), stats.length()));
        List<String> fields = new ArrayList<>();
        for (int j = 0; j < fs.fieldStatsLength(); j++) {
            FbsArrayStats a = fs.fieldStats(j);
            fields.add(NAMES.get(j) + ": min=" + hex(a.minAsSegment()) + "/" + a.minPrecision()
                    + " max=" + hex(a.maxAsSegment()) + "/" + a.maxPrecision()
                    + " sum=" + hex(a.sumAsSegment())
                    + " nulls=" + (a.hasNullCount() ? a.nullCount() : "-")
                    + " nans=" + (a.hasNanCount() ? a.nanCount() : "-")
                    + " size=" + (a.hasUncompressedSizeInBytes() ? a.uncompressedSizeInBytes() : "-")
                    + " sorted=" + (a.hasIsSorted() ? a.isSorted() : "-")
                    + " strict=" + (a.hasIsStrictSorted() ? a.isStrictSorted() : "-")
                    + " constant=" + (a.hasIsConstant() ? a.isConstant() : "-"));
        }
        return fields;
    }

    private static String hex(MemorySegment bytes) {
        if (bytes == null) {
            return "-";
        }
        return HexFormat.of().formatHex(bytes.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    private static long jniRowCount(Path file) throws IOException {
        long rows = 0;
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(ScanOptions.builder().build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    rows += reader.getVectorSchemaRoot().getRowCount();
                }
            }
        }
        return rows;
    }
}
