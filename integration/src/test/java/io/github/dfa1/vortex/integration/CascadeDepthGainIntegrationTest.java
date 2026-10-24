package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Does each extra cascade level pay for itself, and does `cascading(3)` match vortex-jni?
///
/// Every column below is shaped so the best encoding is a *chain* (e.g. run-end → frame-of-reference
/// → bit-packing, or ALP → FoR → bit-packing). If a deeper level fails to shrink the file, the
/// cascade is not recursing where Rust's does. vortex-jni always runs Rust's `MAX_CASCADE` (3), so it
/// is the oracle for the depth-3 size.
class CascadeDepthGainIntegrationTest {

    private static final int ROWS = 100_000;
    private static final ColumnName COLUMN = ColumnName.of("v");
    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    static {
        NativeLoader.loadJni();
    }

    /// One column of test data plus the cascade depth at which it must beat the previous depth: the
    /// length of the encoding chain it was built for (run-end → ALP → bit-packing needs 3).
    record Pattern(String name, DType dtype, Object data, int minGainDepth) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Pattern> patterns() {
        Random rng = new Random(42L);

        // run-end → (values FoR+bitpack, ends bitpack): needs 3 levels to shrink the run values
        long[] bigRuns = new long[ROWS];
        int row = 0;
        while (row < ROWS) {
            long value = 1_000_000_000_000L + rng.nextInt(1000);
            int run = 20 + rng.nextInt(60);
            for (int j = 0; j < run && row < ROWS; j++, row++) {
                bigRuns[row] = value;
            }
        }

        // dict → (codes bitpack, values FoR+bitpack)
        long[] lowCardBig = new long[ROWS];
        long[] pool = new long[20];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = 1_000_000_000_000L + rng.nextInt(100_000);
        }
        for (int i = 0; i < ROWS; i++) {
            lowCardBig[i] = pool[rng.nextInt(pool.length)];
        }

        // plain FoR → bitpack
        long[] offsetSmall = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            offsetSmall[i] = 5_000_000_000L + rng.nextInt(4096);
        }

        // ZigZag → bitpack
        long[] signedSmall = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            signedSmall[i] = rng.nextInt(2001) - 1000;
        }

        // ALP → (encoded ints FoR → bitpack)
        double[] price2dp = new double[ROWS];
        double px = 100.0;
        for (int i = 0; i < ROWS; i++) {
            px = Math.round((px + rng.nextGaussian() * 0.5) * 100.0) / 100.0;
            price2dp[i] = px;
        }

        // run-end over ALP-able doubles
        double[] doubleRuns = new double[ROWS];
        row = 0;
        while (row < ROWS) {
            double value = Math.round(rng.nextDouble() * 1_000_000) / 100.0;
            int run = 20 + rng.nextInt(60);
            for (int j = 0; j < run && row < ROWS; j++, row++) {
                doubleRuns[row] = value;
            }
        }

        // dict → (codes bitpack, values varbin/FSST)
        String[] tickers = {"ACME", "WIDGET", "GADGET", "MEGA", "PIXEL", "GLOBEX", "INITECH", "HOOLI"};
        String[] lowCardText = new String[ROWS];
        for (int i = 0; i < ROWS; i++) {
            lowCardText[i] = tickers[rng.nextInt(tickers.length)] + "-corp";
        }

        // FSST → (codes, lengths bitpack)
        String[] urls = new String[ROWS];
        for (int i = 0; i < ROWS; i++) {
            urls[i] = "https://example.com/products/" + rng.nextInt(50_000) + "/reviews?page=" + rng.nextInt(20);
        }

        return Stream.of(
                new Pattern("i64-big-runs", DType.I64, bigRuns, 2),
                new Pattern("i64-lowcard-big", DType.I64, lowCardBig, 1),
                new Pattern("i64-offset-small", DType.I64, offsetSmall, 1),
                new Pattern("i64-signed-small", DType.I64, signedSmall, 1),
                new Pattern("f64-price-2dp", DType.F64, price2dp, 1),
                new Pattern("f64-runs", DType.F64, doubleRuns, 3),
                new Pattern("utf8-lowcard", DType.UTF8, lowCardText, 1),
                new Pattern("utf8-urls", DType.UTF8, urls, 1));
    }

    @ParameterizedTest
    @MethodSource("patterns")
    void deeperCascade_neverGrows_andGainsWhereChainsExist(Pattern pattern, @TempDir Path tmp) throws IOException {
        // Given
        long[] size = new long[4];

        // When
        for (int depth = 0; depth <= 3; depth++) {
            size[depth] = Files.size(writeJava(tmp, pattern, depth));
        }

        // Then — report
        System.out.printf("[CascadeDepth] %-18s d0=%,d d1=%,d d2=%,d d3=%,d%n",
                pattern.name(), size[0], size[1], size[2], size[3]);

        // Then — a deeper level may do nothing. Scheme choice is made on a ~1% sample, as in Rust,
        // so a deeper cascade can mispredict slightly (measured up to 1.3%); more than that is a bug
        for (int depth = 1; depth <= 3; depth++) {
            assertThat(size[depth])
                    .as("%s: cascading(%d) vs cascading(%d)", pattern.name(), depth, depth - 1)
                    .isLessThanOrEqualTo((long) (size[depth - 1] * 1.02));
        }
        // Then — the chain this pattern was built for must pay off at its depth
        if (pattern.minGainDepth() > 0) {
            assertThat(size[pattern.minGainDepth()])
                    .as("%s: cascading(%d) must beat cascading(%d)", pattern.name(), pattern.minGainDepth(),
                            pattern.minGainDepth() - 1)
                    .isLessThan(size[pattern.minGainDepth() - 1]);
        }
    }

    @ParameterizedTest
    @MethodSource("patterns")
    void cascading3_isNotLargerThanJni(Pattern pattern, @TempDir Path tmp) throws IOException {
        // Given
        Path javaFile = writeJava(tmp, pattern, 3);

        // When
        Path jniFile = writeJni(tmp, pattern);
        long javaSize = Files.size(javaFile);
        long jniSize = Files.size(jniFile);

        // Then
        System.out.printf("[CascadeVsJni] %-18s java=%,d jni=%,d java/jni=%.3f%n",
                pattern.name(), javaSize, jniSize, (double) javaSize / jniSize);
        assertThat(javaSize)
                .as("%s: java cascading(3) vs vortex-jni", pattern.name())
                .isLessThanOrEqualTo((long) (jniSize * 1.02));
    }

    /// Layout-level global dictionaries are off on the Java side: vortex-jni has none, and one would
    /// hide the compressor under test (a dict layout turns f64 runs into a trivially small column).
    private static Path writeJava(Path dir, Pattern pattern, int depth) throws IOException {
        Path file = dir.resolve(pattern.name() + "-java-d" + depth + ".vtx");
        DType.Struct schema = new DType.Struct(java.util.List.of(COLUMN), java.util.List.of(pattern.dtype()), false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.cascading(depth).withGlobalDict(false))) {
            writer.writeChunk(Map.of(COLUMN, pattern.data()));
        }
        return file;
    }

    private static Path writeJni(Path dir, Pattern pattern) throws IOException {
        Path file = dir.resolve(pattern.name() + "-jni.vtx");
        ArrowType arrowType = switch (pattern.data()) {
            case long[] _ -> new ArrowType.Int(64, true);
            case double[] _ -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            default -> ArrowType.Utf8.INSTANCE;
        };
        Schema schema = new Schema(java.util.List.of(Field.notNullable("v", arrowType)));
        String uri = file.toAbsolutePath().toUri().toString();
        try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.builder(SESSION, uri, schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            FieldVector vector = root.getVector("v");
            vector.allocateNew();
            switch (pattern.data()) {
                case long[] values -> {
                    for (int i = 0; i < values.length; i++) {
                        ((BigIntVector) vector).setSafe(i, values[i]);
                    }
                }
                case double[] values -> {
                    for (int i = 0; i < values.length; i++) {
                        ((Float8Vector) vector).setSafe(i, values[i]);
                    }
                }
                case String[] values -> {
                    for (int i = 0; i < values.length; i++) {
                        ((VarCharVector) vector).setSafe(i, values[i].getBytes(StandardCharsets.UTF_8));
                    }
                }
                default -> throw new IllegalStateException("unsupported pattern " + pattern.name());
            }
            root.setRowCount(ROWS);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
        return file;
    }
}
