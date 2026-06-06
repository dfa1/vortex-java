package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexInspector;
import io.github.dfa1.vortex.io.VortexReader;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Inspects the OHLC file (same schema and data as RustVsJavaReadBenchmark)
/// to reveal which encodings Rust chose for each column, especially "volume".
class OhlcEncodingInspectionIntegrationTest {

    private static final int ROWS = 200_000;
    private static final int BATCH_SIZE = 50_000;
    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ArrowType F64 = new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    private static final Schema OHLC_SCHEMA = new Schema(List.of(
            Field.notNullable("date", new ArrowType.Date(DateUnit.DAY)),
            Field.notNullable("symbol", ArrowType.Utf8.INSTANCE),
            Field.notNullable("open", F64),
            Field.notNullable("high", F64),
            Field.notNullable("low", F64),
            Field.notNullable("close", F64),
            Field.notNullable("volume", new ArrowType.Int(64, true))
    ));

    static {
        NativeLoader.loadJni();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static void writeOhlc(Path file, int totalRows, int batchSize) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        var rng = new Random(42L);
        double px = 100.0;
        int day = (int) LocalDate.of(2020, 1, 2).toEpochDay();

        try (VortexWriter writer = VortexWriter.create(SESSION, uri, OHLC_SCHEMA, new HashMap<>(), ALLOCATOR)) {
            int rowsLeft = totalRows;
            while (rowsLeft > 0) {
                int n = Math.min(rowsLeft, batchSize);
                try (VectorSchemaRoot root = VectorSchemaRoot.create(OHLC_SCHEMA, ALLOCATOR)) {
                    DateDayVector dateVec = (DateDayVector) root.getVector("date");
                    VarCharVector symbolVec = (VarCharVector) root.getVector("symbol");
                    Float8Vector openVec = (Float8Vector) root.getVector("open");
                    Float8Vector highVec = (Float8Vector) root.getVector("high");
                    Float8Vector lowVec = (Float8Vector) root.getVector("low");
                    Float8Vector closeVec = (Float8Vector) root.getVector("close");
                    BigIntVector volumeVec = (BigIntVector) root.getVector("volume");

                    dateVec.allocateNew(n);
                    symbolVec.allocateNew(n);
                    openVec.allocateNew(n);
                    highVec.allocateNew(n);
                    lowVec.allocateNew(n);
                    closeVec.allocateNew(n);
                    volumeVec.allocateNew(n);

                    for (int i = 0; i < n; i++) {
                        double ret = rng.nextGaussian() * 0.02;
                        double o = round(px * (1 + ret * 0.3));
                        double c = round(px * (1 + ret));
                        double spread = Math.abs(px * rng.nextDouble() * 0.03);
                        double h = round(Math.max(o, c) + spread);
                        double l = round(Math.min(o, c) - spread);
                        dateVec.setSafe(i, day++);
                        symbolVec.setSafe(i, "ACME".getBytes(StandardCharsets.UTF_8));
                        openVec.setSafe(i, o);
                        highVec.setSafe(i, h);
                        lowVec.setSafe(i, l);
                        closeVec.setSafe(i, c);
                        volumeVec.setSafe(i, Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000)));
                        px = c;
                    }
                    root.setRowCount(n);

                    try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                         ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                        writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
                    }
                }
                rowsLeft -= n;
            }
        }
    }

    @Test
    void inspect_ohlcFile_showsColumnEncodings(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("ohlc.vtx");
        writeOhlc(file, ROWS, BATCH_SIZE);

        // When
        String report;
        try (VortexReader vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            report = VortexInspector.inspect(vf);
        }

        // Then
        System.out.println(report);
        assertThat(report).contains("volume");
        assertThat(report).contains("Used encodings:");
    }
}
