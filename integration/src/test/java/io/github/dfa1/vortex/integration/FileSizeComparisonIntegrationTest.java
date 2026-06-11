package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.Registry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// File-size comparison: CSV (baseline) vs JNI/Rust writer (oracle) vs Java writer (SUT).
///
/// Schema: symbol(Utf8), date(I32), open/high/low/close(F64), volume(I64) — 7 columns.
/// Both Vortex writers use cascading(3) to match the Rust default compression pipeline.
class FileSizeComparisonIntegrationTest {

    private static final int TOTAL_ROWS = 100_000;
    private static final int BATCH_SIZE = 50_000;

    private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
            List.of("symbol", "date", "open", "high", "low", "close", "volume"),
            List.of(
                    new DType.Utf8(false),
                    new DType.Primitive(PType.I32, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.I64, false)
            ),
            false
    );

    private static final Schema JNI_SCHEMA = new Schema(List.of(
            Field.notNullable("symbol", new ArrowType.Utf8()),
            Field.notNullable("date", new ArrowType.Date(DateUnit.DAY)),
            Field.notNullable("open", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            Field.notNullable("high", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            Field.notNullable("low", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            Field.notNullable("close", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            Field.notNullable("volume", new ArrowType.Int(64, true))
    ));

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    static {
        NativeLoader.loadJni();
    }

    // ── Writers ───────────────────────────────────────────────────────────────

    private static Path writeCsv(Path dir, List<OhlcGenerator.OhlcBatch> batches) throws IOException {
        Path file = dir.resolve("ohlc.csv");
        try (BufferedWriter csv = Files.newBufferedWriter(file)) {
            csv.write("symbol,date,open,high,low,close,volume\n");
            for (OhlcGenerator.OhlcBatch b : batches) {
                for (int i = 0; i < b.dates().length; i++) {
                    csv.write(b.symbols()[i] + "," + LocalDate.ofEpochDay(b.dates()[i]) + ","
                                      + b.open()[i] + "," + b.high()[i] + ","
                                      + b.low()[i] + "," + b.close()[i] + "," + b.volume()[i] + "\n");
                }
            }
        }
        return file;
    }

    private static Path writeJava(Path dir, List<OhlcGenerator.OhlcBatch> batches) throws IOException {
        return writeJava(dir, "ohlc-java.vtx", WriteOptions.cascading(3), batches);
    }

    private static Path writeJavaZstd(Path dir, List<OhlcGenerator.OhlcBatch> batches) throws IOException {
        return writeJava(dir, "ohlc-java-zstd.vtx", WriteOptions.cascading(3).withZstd(true), batches);
    }

    private static Path writeJava(Path dir, String filename, WriteOptions opts,
            List<OhlcGenerator.OhlcBatch> batches) throws IOException {
        Path file = dir.resolve(filename);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, JAVA_SCHEMA, opts)) {
            for (OhlcGenerator.OhlcBatch b : batches) {
                writer.writeChunk(Map.of(
                        "symbol", b.symbols(), "date", b.dates(),
                        "open", b.open(), "high", b.high(),
                        "low", b.low(), "close", b.close(), "volume", b.volume()));
            }
        }
        return file;
    }

    private static Path writeJni(Path dir, List<OhlcGenerator.OhlcBatch> batches) throws IOException {
        Path file = dir.resolve("ohlc-jni.vtx");
        String uri = file.toAbsolutePath().toUri().toString();
        try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.create(
                SESSION, uri, JNI_SCHEMA, new HashMap<>(), ALLOCATOR)) {
            for (OhlcGenerator.OhlcBatch b : batches) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, ALLOCATOR)) {
                    VarCharVector symbolVec = (VarCharVector) root.getVector("symbol");
                    DateDayVector dateVec = (DateDayVector) root.getVector("date");
                    Float8Vector openVec = (Float8Vector) root.getVector("open");
                    Float8Vector highVec = (Float8Vector) root.getVector("high");
                    Float8Vector lowVec = (Float8Vector) root.getVector("low");
                    Float8Vector closeVec = (Float8Vector) root.getVector("close");
                    BigIntVector volVec = (BigIntVector) root.getVector("volume");

                    int n = b.dates().length;
                    symbolVec.allocateNew();
                    dateVec.allocateNew(n);
                    openVec.allocateNew(n);
                    highVec.allocateNew(n);
                    lowVec.allocateNew(n);
                    closeVec.allocateNew(n);
                    volVec.allocateNew(n);

                    for (int i = 0; i < n; i++) {
                        symbolVec.setSafe(i, b.symbols()[i].getBytes(StandardCharsets.UTF_8));
                        dateVec.setSafe(i, b.dates()[i]);
                        openVec.setSafe(i, b.open()[i]);
                        highVec.setSafe(i, b.high()[i]);
                        lowVec.setSafe(i, b.low()[i]);
                        closeVec.setSafe(i, b.close()[i]);
                        volVec.setSafe(i, b.volume()[i]);
                    }
                    root.setRowCount(n);

                    try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                         ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                        writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
                    }
                }
            }
        }
        return file;
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    @Test
    void fileSizeComparison(@TempDir Path tmp) throws IOException {
        // Given
        List<OhlcGenerator.OhlcBatch> batches = OhlcGenerator.generate(TOTAL_ROWS, BATCH_SIZE);

        // When
        Path csvFile = writeCsv(tmp, batches);
        Path jniFile = writeJni(tmp, batches);
        Path javaFile = writeJava(tmp, batches);

        long csvSize = Files.size(csvFile);
        long jniSize = Files.size(jniFile);
        long javaSize = Files.size(javaFile);

        // Then — report
        System.out.printf(
                "[FileSizeComparison] %,d rows  CSV=%,d bytes (%.1f MB)  JNI=%,d bytes (%.1f MB)  Java=%,d bytes (%.1f MB)  Java/JNI=%.2fx  CSV/Java=%.1fx%n",
                TOTAL_ROWS,
                csvSize, csvSize / 1_048_576.0,
                jniSize, jniSize / 1_048_576.0,
                javaSize, javaSize / 1_048_576.0,
                (double) javaSize / jniSize,
                (double) csvSize / javaSize);

        // Then — Java beats CSV
        assertThat(javaSize).isLessThan(csvSize);

        // Then — Java within 2x of JNI (both use cascading(3))
        assertThat(javaSize).isLessThan(jniSize * 2);

        // Then — Java file is readable with correct row count
        var totalRows = new java.util.concurrent.atomic.AtomicLong();
        try (VortexReader reader = VortexReader.open(javaFile, Registry.loadAll());
             var iter = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("volume"))) {
            iter.forEachRemaining(c -> totalRows.addAndGet(c.<LongArray>column("volume").length()));
        }
        assertThat(totalRows.get()).isEqualTo(TOTAL_ROWS);
    }

    @Test
    void withZstd_smallerFile_and_readable(@TempDir Path tmp) throws IOException {
        // Given
        List<OhlcGenerator.OhlcBatch> batches = OhlcGenerator.generate(TOTAL_ROWS, BATCH_SIZE);

        // When
        Path noZstd = writeJava(tmp, batches);
        Path withZstd = writeJavaZstd(tmp, batches);

        long noZstdSize = Files.size(noZstd);
        long zstdSize = Files.size(withZstd);

        System.out.printf(
                "[ZstdComparison] %,d rows  noZstd=%,d bytes (%.1f MB)  withZstd=%,d bytes (%.1f MB)  ratio=%.2fx%n",
                TOTAL_ROWS, noZstdSize, noZstdSize / 1_048_576.0,
                zstdSize, zstdSize / 1_048_576.0,
                (double) noZstdSize / zstdSize);

        // Then — Zstd produces a smaller file for OHLC F64 data
        assertThat(zstdSize).isLessThan(noZstdSize);

        // Then — Zstd file is readable with correct row count
        var totalRows = new java.util.concurrent.atomic.AtomicLong();
        try (VortexReader reader = VortexReader.open(withZstd, Registry.loadAll());
             var iter = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("volume"))) {
            iter.forEachRemaining(c -> totalRows.addAndGet(c.<LongArray>column("volume").length()));
        }
        assertThat(totalRows.get()).isEqualTo(TOTAL_ROWS);
    }
}
