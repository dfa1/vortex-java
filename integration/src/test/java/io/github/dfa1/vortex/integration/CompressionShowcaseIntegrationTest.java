package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.assertj.core.api.Assertions.assertThat;

/// Regression guard for the cascading compressor's encoding choices.
///
/// Anchors the size ratios documented in
/// `docs/explanation.md#why-cascading-compression`. If a refactor silently
/// swaps the encoding picked for a representative column shape (e.g. ALP
/// gives up on slowly-varying doubles, Dict eats high-cardinality Utf8),
/// the matching assertion here fails and the lookup table in the docs gets
/// re-grounded.
///
/// Lower bounds are deliberately loose: catastrophic regressions only,
/// not 5 % drift.
class CompressionShowcaseIntegrationTest {

    private static final int N = 1_000_000;

    @Test
    void monotonicTimestamps_compressVia_FoR_Bitpacked(@TempDir Path tmp) throws IOException {
        // Given — UNIX seconds incrementing by 1 per row. Cascade should pick
        // FoR (subtract base) then Bitpacked on the small residuals.
        long base = LocalDate.of(2026, 1, 1).toEpochDay() * 86_400L;
        long[] data = new long[N];
        for (int i = 0; i < N; i++) {
            data[i] = base + i;
        }

        long cascadeSize = writeCascade(tmp, "monotonic", new DType.Primitive(PType.I64, false), data);
        long rawBytes = (long) N * 8;

        // Then — at least 2.5x smaller than raw I64. Real ratio on M5 is ~3.2x.
        assertThat((double) rawBytes / cascadeSize).isGreaterThan(2.5);
    }

    @Test
    void lowCardCategorical_compressVia_Dict(@TempDir Path tmp) throws IOException {
        // Given — 5 distinct ticker symbols cycled across all rows. Even with
        // cascade off Dict wins (first acceptor for Utf8); cascade can't make
        // it worse.
        String[] tickers = {"AAPL", "MSFT", "NVDA", "GOOGL", "AMZN"};
        String[] data = new String[N];
        for (int i = 0; i < N; i++) {
            data[i] = tickers[i % tickers.length];
        }

        long cascadeSize = writeCascade(tmp, "lowcard", new DType.Utf8(false), data);
        long rawBytes = totalUtf8Bytes(data);

        // Then — at least 3x smaller. Real ratio ~4.2x.
        assertThat((double) rawBytes / cascadeSize).isGreaterThan(3.0);
    }

    @Test
    void randomDoubles_fallbackTo_Primitive(@TempDir Path tmp) throws IOException {
        // Given — uniform random F64. No structure; the cascade must measure
        // alternatives, find none beats raw, and emit Primitive.
        Random rng = new Random(42);
        double[] data = new double[N];
        for (int i = 0; i < N; i++) {
            data[i] = rng.nextDouble();
        }

        long cascadeSize = writeCascade(tmp, "random-f64", new DType.Primitive(PType.F64, false), data);
        long rawBytes = (long) N * 8;

        // Then — within 5 % of raw. No compression possible but cascade must
        // not make it bigger.
        assertThat(cascadeSize).isLessThan((long) (rawBytes * 1.05));
    }

    @Test
    void alpFriendlyDoubles_compressVia_ALP_FoR_Bitpacked(@TempDir Path tmp) throws IOException {
        // Given — slowly varying prices around 100, two-decimal precision.
        // ALP detects the scale factor; its mantissa child cascades to
        // FoR + Bitpacked.
        Random rng = new Random(42);
        double[] data = new double[N];
        double price = 100.00;
        for (int i = 0; i < N; i++) {
            price += (rng.nextDouble() - 0.5) * 0.02;
            data[i] = Math.round(price * 100.0) / 100.0;
        }

        long cascadeSize = writeCascade(tmp, "alp", new DType.Primitive(PType.F64, false), data);
        long rawBytes = (long) N * 8;

        // Then — at least 5x smaller. Real ratio ~6.4x.
        assertThat((double) rawBytes / cascadeSize).isGreaterThan(5.0);
    }

    @Test
    void rleInt_compressVia_RunEnd(@TempDir Path tmp) throws IOException {
        // Given — 10k-row runs of the same value. RunEnd reduces N rows to
        // ~N/run_length pairs; both children compress further via Bitpacked.
        int[] data = new int[N];
        int run = 10_000;
        int v = 1;
        for (int i = 0; i < N; i++) {
            if (i % run == 0) {
                v += 1;
            }
            data[i] = v;
        }

        long cascadeSize = writeCascade(tmp, "rle", new DType.Primitive(PType.I32, false), data);
        long rawBytes = (long) N * 4;

        // Then — at least 1000x smaller. Real ratio ~2493x.
        assertThat((double) rawBytes / cascadeSize).isGreaterThan(1000.0);
    }

    @Test
    void highCardStrings_routeTo_FSST_via_dictGate(@TempDir Path tmp) throws IOException {
        // Given — all-distinct random 6-char strings. Dict has > 50 % distinct
        // on the sample so its cardinality gate returns notApplicable and the
        // cascade rotates to FSST.
        Random rng = new Random(42);
        String[] data = new String[N];
        byte[] buf = new byte[6];
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < 6; k++) {
                buf[k] = (byte) ('a' + rng.nextInt(26));
            }
            data[i] = new String(buf);
        }

        long noCascadeSize = writeNoCascade(tmp, "hicard-flat", new DType.Utf8(false), data);
        long cascadeSize = writeCascade(tmp, "hicard-cas", new DType.Utf8(false), data);

        // Then — FSST cascade strictly beats the no-cascade Dict fallback.
        // (Both lose to raw bytes on truly random short strings; that's
        // fundamental, not a bug.)
        assertThat(cascadeSize).isLessThan(noCascadeSize);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long writeCascade(Path dir, String name, DType dtype, Object data) throws IOException {
        return write(dir.resolve(name + ".cas.vtx"), dtype, data,
                WriteOptions.cascading(3).withGlobalDict(false));
    }

    private static long writeNoCascade(Path dir, String name, DType dtype, Object data) throws IOException {
        return write(dir.resolve(name + ".flat.vtx"), dtype, data,
                WriteOptions.defaults().withGlobalDict(false));
    }

    private static long write(Path file, DType dtype, Object data, WriteOptions opts) throws IOException {
        DType.Struct schema = new DType.Struct(List.of("v"), List.of(dtype), false);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, schema, opts)) {
            Map<String, Object> col = new HashMap<>();
            col.put("v", data);
            writer.writeChunk(col);
        }
        return Files.size(file);
    }

    private static long totalUtf8Bytes(String[] data) {
        long total = 0;
        for (String s : data) {
            total += s.length();
        }
        return total;
    }
}
