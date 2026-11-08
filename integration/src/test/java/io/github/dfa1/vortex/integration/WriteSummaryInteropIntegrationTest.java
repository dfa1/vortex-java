package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexColumnStatistics;
import dev.vortex.api.VortexWriteSummary;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ArrayStats;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.WriteSummary;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/// [VortexWriter#finish()]'s summary against vortex-jni's `VortexWriter.finish()` for the same batches
/// (#664): the row count and, per column, the null and NaN counts and the bounds, which both take
/// from the footer's file statistics. The compressed sizes depend on each writer's encodings, so
/// those are checked against the file instead: Rust's `compressed_column_sizes` sums the segments
/// of each column's layout subtree.
class WriteSummaryInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 1000;
    private static final int BATCHES = 2;
    private static final List<ColumnName> NAMES = List.of("i64", "i32n", "f64", "u64", "utf8", "bin", "booln", "dec")
            .stream().map(ColumnName::of).toList();

    @BeforeAll
    static void loadJni() {
        NativeLoader.loadJni();
    }

    @Test
    void javaSummaryReportsWhatJniReports(@TempDir Path tmp) throws Exception {
        // Given
        VortexWriteSummary jni = writeJni(tmp.resolve("jni.vortex"));

        // When
        WriteSummary result = writeJava(tmp.resolve("java.vortex"));

        // Then
        assertThat(result.rowCount()).isEqualTo(jni.rowCount());
        assertThat(columns(result)).isEqualTo(columns(jni));
    }

    @Test
    void sizesAreTheFilesSizes(@TempDir Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("java.vortex");

        // When
        WriteSummary result = writeJava(file);

        // Then — the file size includes the trailer, and each column owns its layout subtree's segments
        assertThat(result.fileSize()).isEqualTo(Files.size(file));
        try (VortexReader reader = VortexReader.open(file)) {
            List<Long> expected = new ArrayList<>();
            for (Layout column : reader.layout().children()) {
                expected.add(segmentBytes(column, reader));
            }
            assertThat(result.columns().values()).extracting(WriteSummary.Column::compressedSize)
                    .isEqualTo(expected);
        }
    }

    // ── data ────────────────────────────────────────────────────────────────

    private static boolean valid(int row) {
        return row % 7 != 0;
    }

    private static double f64(int row) {
        return row % 11 == 0 ? Double.NaN : row * 0.5;
    }

    private static String utf8(int row) {
        // a value past 64 bytes: its bound is cut and inexact, and both writers report the cut one
        return row == 3 ? "a".repeat(80) : "m" + row;
    }

    private static byte[] bin(int row) {
        return new byte[]{1, (byte) row};
    }

    private static BigDecimal dec(int row) {
        return valid(row) ? BigDecimal.valueOf(row * 101L, 2) : null;
    }

    // ── writers ─────────────────────────────────────────────────────────────

    private static VortexWriteSummary writeJni(Path file) throws Exception {
        Schema arrow = new Schema(List.of(
                Field.notNullable("i64", new ArrowType.Int(64, true)),
                Field.nullable("i32n", new ArrowType.Int(32, true)),
                Field.notNullable("f64", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("u64", new ArrowType.Int(64, false)),
                Field.notNullable("utf8", ArrowType.Utf8.INSTANCE),
                Field.notNullable("bin", ArrowType.Binary.INSTANCE),
                Field.nullable("booln", ArrowType.Bool.INSTANCE),
                Field.nullable("dec", new ArrowType.Decimal(10, 2, 128))));
        try (dev.vortex.api.VortexWriter w = dev.vortex.api.VortexWriter.builder(SESSION,
                file.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
                    root.allocateNew();
                    for (int i = 0; i < ROWS; i++) {
                        int row = batch * ROWS + i;
                        ((BigIntVector) root.getVector("i64")).setSafe(i, row * 3L - 100);
                        IntVector i32n = (IntVector) root.getVector("i32n");
                        BitVector booln = (BitVector) root.getVector("booln");
                        DecimalVector dec = (DecimalVector) root.getVector("dec");
                        if (valid(row)) {
                            i32n.setSafe(i, row % 1000);
                            booln.setSafe(i, row % 2);
                            dec.setSafe(i, dec(row));
                        } else {
                            i32n.setNull(i);
                            booln.setNull(i);
                            dec.setNull(i);
                        }
                        ((Float8Vector) root.getVector("f64")).setSafe(i, f64(row));
                        ((UInt8Vector) root.getVector("u64")).setSafe(i, row * 7L);
                        ((VarCharVector) root.getVector("utf8")).setSafe(i, utf8(row).getBytes(StandardCharsets.UTF_8));
                        ((VarBinaryVector) root.getVector("bin")).setSafe(i, bin(row));
                    }
                    root.setRowCount(ROWS);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, schema);
                        w.writeBatch(array.memoryAddress(), schema.memoryAddress());
                    }
                }
            }
            return w.finish();
        }
    }

    private static WriteSummary writeJava(Path file) throws IOException {
        DType.Struct schema = new DType.Struct(NAMES, List.of(DTypes.I64, DTypes.I32.asNullable(), DTypes.F64,
                DTypes.U64, DTypes.UTF8, DTypes.BINARY, DTypes.BOOL.asNullable(), DType.Decimal.of(10, 2, true)), false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter w = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int batch = 0; batch < BATCHES; batch++) {
                long[] i64 = new long[ROWS];
                Integer[] i32n = new Integer[ROWS];
                double[] f64 = new double[ROWS];
                long[] u64 = new long[ROWS];
                String[] utf8 = new String[ROWS];
                byte[][] bin = new byte[ROWS][];
                Boolean[] booln = new Boolean[ROWS];
                BigDecimal[] dec = new BigDecimal[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    int row = batch * ROWS + i;
                    i64[i] = row * 3L - 100;
                    i32n[i] = valid(row) ? row % 1000 : null;
                    f64[i] = f64(row);
                    u64[i] = row * 7L;
                    utf8[i] = utf8(row);
                    bin[i] = bin(row);
                    booln[i] = valid(row) ? row % 2 == 1 : null;
                    dec[i] = dec(row);
                }
                List<Object> values = List.of(i64, i32n, f64, u64, utf8, bin, booln, dec);
                Map<ColumnName, Object> chunk = new LinkedHashMap<>();
                for (int c = 0; c < NAMES.size(); c++) {
                    chunk.put(NAMES.get(c), values.get(c));
                }
                w.writeChunk(chunk);
            }
            return w.finish();
        }
    }

    // ── comparison ──────────────────────────────────────────────────────────

    private static List<String> columns(WriteSummary summary) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<ColumnName, WriteSummary.Column> e : summary.columns().entrySet()) {
            ArrayStats s = e.getValue().stats();
            out.add(e.getKey() + ": nulls=" + s.nullCount() + " nans=" + s.nanCount()
                    + " lower=" + text(s.min()) + " upper=" + text(s.max()));
        }
        return out;
    }

    private static List<String> columns(VortexWriteSummary summary) {
        List<String> out = new ArrayList<>();
        for (VortexColumnStatistics c : summary.columnStatistics()) {
            out.add(NAMES.get(c.columnIndex()) + ": nulls=" + count(c.nullValueCount()) + " nans=" + count(c.nanValueCount())
                    + " lower=" + text(c.lowerBound()) + " upper=" + text(c.upperBound()));
        }
        return out;
    }

    private static Long count(OptionalLong count) {
        return count.isPresent() ? count.getAsLong() : null;
    }

    private static String text(Optional<Object> value) {
        return text(value.orElse(null));
    }

    /// One spelling per value for both writers: vortex-jni boxes integers by width (`Integer`,
    /// `Long`, `BigInteger`) where the summary has a `Long`, and its decimals carry their own scale.
    private static String text(Object value) {
        return switch (value) {
            case null -> "-";
            case byte[] bytes -> HexFormat.of().formatHex(bytes);
            case BigDecimal decimal -> decimal.stripTrailingZeros().toPlainString();
            default -> value.toString();
        };
    }

    private static long segmentBytes(Layout layout, VortexReader reader) {
        long bytes = 0;
        for (int segment : layout.segments()) {
            bytes += reader.footer().segmentSpecs().get(segment).length();
        }
        for (Layout child : layout.children()) {
            bytes += segmentBytes(child, reader);
        }
        return bytes;
    }
}
