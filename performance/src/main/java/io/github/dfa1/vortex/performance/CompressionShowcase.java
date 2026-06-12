package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/// Prints encoded file sizes for several representative columns, with and
/// without the cascading compressor, so readers can see which encodings the
/// library reaches for on different data shapes.
public final class CompressionShowcase {

    private static final int N = 1_000_000;

    private CompressionShowcase() {
    }

    public static void main(String[] args) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-showcase");

        header();
        run(tmp, "monotonic-timestamps",
                "I64 epoch seconds, +1s per row (sensor stream / log clock)",
                new DType.Primitive(PType.I64, false),
                monotonicTimestamps());
        run(tmp, "low-card-categorical",
                "Utf8, 5 distinct ticker symbols cycled across all rows",
                new DType.Utf8(false),
                lowCardSymbols());
        run(tmp, "random-doubles",
                "F64, uniform random in [0, 1) (worst case — no structure)",
                new DType.Primitive(PType.F64, false),
                randomDoubles());
        run(tmp, "alp-friendly-doubles",
                "F64, slowly varying prices (~0.01 increments around 100)",
                new DType.Primitive(PType.F64, false),
                alpFriendlyDoubles());
        run(tmp, "rle-int",
                "I32, long runs (~10k each) of the same value",
                new DType.Primitive(PType.I32, false),
                rleInt());
        run(tmp, "highcard-strings",
                "Utf8, all-distinct random 6-char ASCII strings",
                new DType.Utf8(false),
                highCardStrings());

        // Clean up
        for (Path f : Files.list(tmp).toList()) {
            Files.deleteIfExists(f);
        }
        Files.deleteIfExists(tmp);
    }

    private static void header() {
        System.out.printf("%-26s %15s %15s %15s %10s%n",
                "dataset", "raw bytes", "no-cascade", "cascade depth 3", "ratio");
        System.out.println("-".repeat(85));
    }

    private static void run(Path dir, String name, String description, DType dtype, Object data)
            throws IOException {
        DType.Struct schema = new DType.Struct(List.of("v"), List.of(dtype), false);

        Path noCascade = dir.resolve(name + ".no-cascade.vtx");
        write(noCascade, schema, data, WriteOptions.defaults().withGlobalDict(false));

        Path cascade = dir.resolve(name + ".cascade.vtx");
        write(cascade, schema, data, WriteOptions.cascading(3).withGlobalDict(false));

        long raw = rawBytes(dtype, data);
        long flat = Files.size(noCascade);
        long cas = Files.size(cascade);
        double ratio = (double) raw / cas;

        System.out.printf("%-26s %,15d %,15d %,15d %9.2fx%n", name, raw, flat, cas, ratio);
        System.out.printf("  %s%n%n", description);
    }

    private static void write(Path file, DType.Struct schema, Object data, WriteOptions opts)
            throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, schema, opts)) {
            Map<String, Object> col = new HashMap<>();
            col.put("v", data);
            writer.writeChunk(col);
        }
    }

    private static long rawBytes(DType dtype, Object data) {
        if (dtype instanceof DType.Primitive p) {
            return (long) ((switch (data) {
                case long[] a -> a.length;
                case int[] a -> a.length;
                case double[] a -> a.length;
                case float[] a -> a.length;
                case short[] a -> a.length;
                case byte[] a -> a.length;
                default -> throw new IllegalStateException();
            })) * p.ptype().byteSize();
        }
        if (dtype instanceof DType.Utf8) {
            long total = 0;
            for (String s : (String[]) data) {
                total += s.length();
            }
            return total;
        }
        throw new IllegalStateException();
    }

    // ── Generators ────────────────────────────────────────────────────────────

    private static long[] monotonicTimestamps() {
        long[] out = new long[N];
        long base = LocalDate.of(2026, 1, 1).toEpochDay() * 86_400L;
        for (int i = 0; i < N; i++) {
            out[i] = base + i;
        }
        return out;
    }

    private static String[] lowCardSymbols() {
        String[] tickers = {"AAPL", "MSFT", "NVDA", "GOOGL", "AMZN"};
        String[] out = new String[N];
        for (int i = 0; i < N; i++) {
            out[i] = tickers[i % tickers.length];
        }
        return out;
    }

    private static double[] randomDoubles() {
        Random rng = new Random(42);
        double[] out = new double[N];
        for (int i = 0; i < N; i++) {
            out[i] = rng.nextDouble();
        }
        return out;
    }

    private static double[] alpFriendlyDoubles() {
        Random rng = new Random(42);
        double[] out = new double[N];
        double price = 100.00;
        for (int i = 0; i < N; i++) {
            price += (rng.nextDouble() - 0.5) * 0.02;
            out[i] = Math.round(price * 100.0) / 100.0;
        }
        return out;
    }

    private static int[] rleInt() {
        int[] out = new int[N];
        int run = 10_000;
        int v = 1;
        for (int i = 0; i < N; i++) {
            if (i % run == 0) {
                v += 1;
            }
            out[i] = v;
        }
        return out;
    }

    private static String[] highCardStrings() {
        Random rng = new Random(42);
        String[] out = new String[N];
        byte[] buf = new byte[6];
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < 6; k++) {
                buf[k] = (byte) ('a' + rng.nextInt(26));
            }
            out[i] = new String(buf);
        }
        return out;
    }
}
