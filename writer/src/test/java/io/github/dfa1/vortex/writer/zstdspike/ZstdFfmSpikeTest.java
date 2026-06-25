package io.github.dfa1.vortex.writer.zstdspike;

import io.github.dfa1.vortex.core.testing.OhlcData;

import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Spike measurement: FFM-bound system `libzstd` vs pure-Java aircompressor, on realistic OHLC
/// column bytes. Validates the two things that decide whether we can drop aircompressor from core:
///
/// - **Frame compatibility** - native output must decode with aircompressor and vice versa, so the
///   on-disk `vortex.zstd` format is unchanged regardless of which side wrote it.
/// - **Ratio / speed** - whether native is actually the win it should be (configurable levels).
///
/// Skips when no system libzstd is present (e.g. a minimal CI image). Prints a report to stdout;
/// run with `-pl writer -Dtest=ZstdFfmSpikeTest`.
class ZstdFfmSpikeTest {

    private static final int ROWS = 1_000_000;

    @Test
    void nativeAndAircompressorAreFrameCompatible() {
        Assumptions.assumeTrue(ZstdNative.available(), "system libzstd not found");
        byte[] closeBytes = doubleColumnBytes();

        // Given — aircompressor (pure-Java) and native, each compressing the same column.
        ZstdJavaCompressor airC = new ZstdJavaCompressor();
        byte[] air = aircompress(airC, closeBytes);
        byte[] nat = ZstdNative.compress(closeBytes, 3);

        // When / Then — each side decodes the other's frame back to the original bytes.
        assertThat(ZstdNative.decompress(air, closeBytes.length)).isEqualTo(closeBytes);
        assertThat(airdecompress(nat, closeBytes.length)).isEqualTo(closeBytes);
        // And native round-trips itself.
        assertThat(ZstdNative.decompress(nat, closeBytes.length)).isEqualTo(closeBytes);
    }

    @Test
    void report() {
        Assumptions.assumeTrue(ZstdNative.available(), "system libzstd not found");
        byte[] close = doubleColumnBytes();
        byte[] volume = longColumnBytes();

        System.out.println("\n=== zstd FFM spike — " + ROWS + " rows ===");
        measure("close  (F64)", close);
        measure("volume (I64)", volume);
        System.out.println();
    }

    private static void measure(String label, byte[] src) {
        ZstdJavaCompressor airC = new ZstdJavaCompressor();

        // Warm up both paths so the JIT / native call sites settle before timing.
        for (int i = 0; i < 3; i++) {
            aircompress(airC, src);
            ZstdNative.compress(src, 3);
            ZstdNative.compress(src, 9);
        }

        long t0 = System.nanoTime();
        byte[] air = aircompress(airC, src);
        long airNs = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        byte[] nat3 = ZstdNative.compress(src, 3);
        long nat3Ns = System.nanoTime() - t1;

        long t2 = System.nanoTime();
        byte[] nat9 = ZstdNative.compress(src, 9);
        long nat9Ns = System.nanoTime() - t2;

        System.out.printf("%n%s  (raw %,d B)%n", label, src.length);
        System.out.println("  compress:");
        row("aircompressor (Java)", src.length, air.length, airNs);
        row("native level 3", src.length, nat3.length, nat3Ns);
        row("native level 9", src.length, nat9.length, nat9Ns);

        // Decompress is the read hot path. Both decode the same level-3 native frame (frame format
        // is identical), so this is a like-for-like decode-speed comparison.
        for (int i = 0; i < 5; i++) {
            airdecompress(nat3, src.length);
            ZstdNative.decompress(nat3, src.length);
        }
        long t3 = System.nanoTime();
        airdecompress(nat3, src.length);
        long airDecNs = System.nanoTime() - t3;
        long t4 = System.nanoTime();
        ZstdNative.decompress(nat3, src.length);
        long natDecNs = System.nanoTime() - t4;

        System.out.println("  decompress (same L3 frame):");
        row("aircompressor (Java)", src.length, nat3.length, airDecNs);
        row("native", src.length, nat3.length, natDecNs);
    }

    private static void row(String name, int raw, int compressed, long ns) {
        double ratio = (double) raw / compressed;
        double mbPerSec = (raw / 1_048_576.0) / (ns / 1_000_000_000.0);
        System.out.printf("  %-22s %,10d B   %5.2fx   %7.1f MB/s%n", name, compressed, ratio, mbPerSec);
    }

    private static byte[] aircompress(ZstdJavaCompressor c, byte[] src) {
        int max = c.maxCompressedLength(src.length);
        byte[] dst = new byte[max];
        int n = c.compress(src, 0, src.length, dst, 0, max);
        return Arrays.copyOf(dst, n);
    }

    private static byte[] airdecompress(byte[] src, int decompressedSize) {
        byte[] out = new byte[decompressedSize];
        int n = new ZstdJavaDecompressor().decompress(src, 0, src.length, out, 0, out.length);
        return Arrays.copyOf(out, n);
    }

    private static byte[] doubleColumnBytes() {
        List<OhlcData.Batch> batches = OhlcData.generate(ROWS, ROWS);
        double[] close = batches.getFirst().close();
        ByteBuffer bb = ByteBuffer.allocate(close.length * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : close) {
            bb.putDouble(v);
        }
        return bb.array();
    }

    private static byte[] longColumnBytes() {
        List<OhlcData.Batch> batches = OhlcData.generate(ROWS, ROWS);
        long[] volume = batches.getFirst().volume();
        ByteBuffer bb = ByteBuffer.allocate(volume.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : volume) {
            bb.putLong(v);
        }
        return bb.array();
    }
}
