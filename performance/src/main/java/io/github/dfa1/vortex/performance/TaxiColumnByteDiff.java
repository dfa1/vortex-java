package io.github.dfa1.vortex.performance;

import dev.vortex.api.Session;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Layout;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.parquet.ParquetImporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Per-column byte attribution: walks the Layout tree of two Vortex files and prints
/// the byte cost of each top-level struct field side-by-side. Use to locate which
/// columns drive the size gap between writers.
///
/// Run: java -cp performance/target/benchmarks.jar \
///        --enable-native-access=ALL-UNNAMED \
///        --add-opens java.base/java.nio=ALL-UNNAMED \
///        io.github.dfa1.vortex.performance.TaxiColumnByteDiff
public final class TaxiColumnByteDiff {

    private static final Path PARQUET =
            Path.of(System.getProperty("java.io.tmpdir"), "yellow_tripdata_2024-01.parquet");
    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    private TaxiColumnByteDiff() {
    }

    public static void main(String[] args) throws Exception {
        if (!Files.exists(PARQUET)) {
            System.err.println("Missing: " + PARQUET);
            System.exit(1);
        }
        Path jniVortex = Files.createTempFile("taxi-jni", ".vortex");
        Path javaVortex = Files.createTempFile("taxi-java", ".vortex");
        try {
            TaxiLayoutInspector.class.getMethod("main", String[].class); // ensure same classpath
            // Write JNI side via TaxiLayoutInspector's helper — duplicated here to keep dep simple.
            writeJni(jniVortex);
            ParquetImporter.importParquet(PARQUET, javaVortex);

            long javaTotal = Files.size(javaVortex);
            long jniTotal = Files.size(jniVortex);
            System.out.printf("Java total: %,d bytes (%.1f MB)%n", javaTotal, javaTotal / 1_048_576.0);
            System.out.printf("JNI  total: %,d bytes (%.1f MB)%n", jniTotal, jniTotal / 1_048_576.0);
            System.out.printf("Delta:      %,d bytes (%.2f MB)%n%n",
                    javaTotal - jniTotal, (javaTotal - jniTotal) / 1_048_576.0);

            Map<String, Long> javaCols = perColumnBytes(javaVortex);
            Map<String, Long> jniCols = perColumnBytes(jniVortex);

            System.out.printf("%-25s %15s %15s %15s%n", "column", "Java bytes", "JNI bytes", "delta");
            System.out.println("-".repeat(73));
            long sumDelta = 0;
            for (String col : javaCols.keySet()) {
                long j = javaCols.get(col);
                long r = jniCols.getOrDefault(col, 0L);
                long d = j - r;
                sumDelta += d;
                System.out.printf("%-25s %,15d %,15d %+,15d%n", col, j, r, d);
            }
            System.out.println("-".repeat(73));
            System.out.printf("%-25s %15s %15s %+,15d%n", "(sum of per-col deltas)", "", "", sumDelta);
        } finally {
            Files.deleteIfExists(jniVortex);
            Files.deleteIfExists(javaVortex);
        }
    }

    private static void writeJni(Path target) throws Exception {
        // Reuse TaxiLayoutInspector's package-private writeJni via reflection-free re-call:
        // the inspector's main writes to its own temp paths, so instead invoke its writeJni
        // directly. Done via making it package-private; simpler: just call ParquetImporter
        // for both and diff that alone (but we need JNI baseline).
        //
        // Pragmatic path: invoke TaxiLayoutInspector.writeJni via reflection.
        var m = TaxiLayoutInspector.class.getDeclaredMethod("writeJni", Path.class, Path.class);
        m.setAccessible(true);
        m.invoke(null, PARQUET, target);
    }

    private static Map<String, Long> perColumnBytes(Path file) throws IOException {
        try (VortexReader r = VortexReader.open(file, ReadRegistry.loadAll())) {
            Layout root = r.layout();
            List<SegmentSpec> segs = r.footer().segmentSpecs();
            DType dtype = r.dtype();
            if (!(dtype instanceof DType.Struct s)) {
                throw new IllegalStateException("expected struct root, got: " + dtype);
            }
            Layout structLayout = unwrapToStruct(root);
            List<String> names = s.fieldNames();
            List<Layout> children = structLayout.children();
            Map<String, Long> out = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                out.put(names.get(i), sumLeafSegments(children.get(i), segs));
            }
            return out;
        }
    }

    private static Layout unwrapToStruct(Layout layout) {
        while (!layout.isStruct()) {
            if (layout.children().isEmpty()) {
                throw new IllegalStateException("hit leaf before struct: " + layout.layoutId());
            }
            layout = layout.children().get(0);
        }
        return layout;
    }

    private static long sumLeafSegments(Layout layout, List<SegmentSpec> segs) {
        long total = 0;
        for (Integer idx : layout.segments()) {
            total += segs.get(idx).length();
        }
        for (Layout child : layout.children()) {
            total += sumLeafSegments(child, segs);
        }
        return total;
    }

    private static double mb(long bytes) {
        return bytes / 1_048_576.0;
    }
}
