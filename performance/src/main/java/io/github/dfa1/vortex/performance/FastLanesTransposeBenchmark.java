package io.github.dfa1.vortex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Head-to-head micro-benchmark for the FastLanes index math used by the delta encoding's
/// transpose and undelta loops, comparing the original per-element arithmetic
/// (`%`/`/` + `ORDER[]` lookup) against the precomputed permutation tables now shipped in
/// `FastLanes`.
///
/// Both kernels permute chunk-by-chunk directly into a large destination array, so the
/// working set scales with `size`: at `size == 1024` it is a single L1-resident chunk
/// (pure index-math cost), and at large `size` the scatter spans L2/SLC/DRAM (memory-bound,
/// where the win shrinks but persists because faster address generation keeps more scatter
/// misses in flight). The crossover is the whole point of the sweep.
///
/// Run: ./bench FastLanesTransposeBenchmark
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FastLanesTransposeBenchmark {

    private static final int CHUNK = 1024;
    private static final int[] ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    /// Precomputed logical-to-transposed permutation for one chunk.
    private static final int[] TRANSPOSE = new int[CHUNK];
    /// Precomputed `iterateIndex` base per row for the 64-bit (typeBits == 64) path.
    private static final int[] ITERATE_BASE = new int[64];

    static {
        for (int i = 0; i < CHUNK; i++) {
            int lane = i % 16;
            int order = (i / 16) % 8;
            int row = i / 128;
            TRANSPOSE[i] = lane * 64 + ORDER[order] * 8 + row;
        }
        for (int row = 0; row < 64; row++) {
            ITERATE_BASE[row] = ORDER[row / 8] * 16 + (row % 8) * 128;
        }
    }

    /// Working-set sizes: 8 KB (L1) -> 256 MB (DRAM) in `long` elements.
    @Param({"1024", "32768", "262144", "2097152", "8388608", "33554432"})
    private int size;

    private long[] src;
    private long[] dst;
    private int numChunks;

    @Setup(Level.Trial)
    public void setup() {
        numChunks = size / CHUNK;
        src = new long[size];
        dst = new long[size];
        Random random = new Random(42);
        for (int i = 0; i < size; i++) {
            src[i] = random.nextLong();
        }
    }

    private static int transposeIndex(int idx) {
        int lane = idx % 16;
        int order = (idx / 16) % 8;
        int row = idx / 128;
        return lane * 64 + ORDER[order] * 8 + row;
    }

    private static int transposeIndexShift(int idx) {
        int lane = idx & 15;
        int order = (idx >> 4) & 7;
        int row = idx >> 7;
        return lane * 64 + ORDER[order] * 8 + row;
    }

    @Benchmark
    public void transposeArithmetic(Blackhole bh) {
        for (int chunk = 0; chunk < numChunks; chunk++) {
            int base = chunk * CHUNK;
            for (int i = 0; i < CHUNK; i++) {
                dst[base + transposeIndex(i)] = src[base + i];
            }
        }
        bh.consume(dst);
    }

    @Benchmark
    public void transposeTable(Blackhole bh) {
        for (int chunk = 0; chunk < numChunks; chunk++) {
            int base = chunk * CHUNK;
            for (int i = 0; i < CHUNK; i++) {
                dst[base + TRANSPOSE[i]] = src[base + i];
            }
        }
        bh.consume(dst);
    }

    @Benchmark
    public void transposeShift(Blackhole bh) {
        for (int chunk = 0; chunk < numChunks; chunk++) {
            int base = chunk * CHUNK;
            for (int i = 0; i < CHUNK; i++) {
                dst[base + transposeIndexShift(i)] = src[base + i];
            }
        }
        bh.consume(dst);
    }

    @Benchmark
    public void undeltaArithmetic(Blackhole bh) {
        int lanes = 16;
        int typeBits = 64;
        for (int chunk = 0; chunk < numChunks; chunk++) {
            int base = chunk * CHUNK;
            for (int lane = 0; lane < lanes; lane++) {
                long prev = src[base + lane];
                for (int row = 0; row < typeBits; row++) {
                    int o = row / 8;
                    int s = row % 8;
                    int idx = ORDER[o] * 16 + s * 128 + lane;
                    long next = src[base + idx] + prev;
                    dst[base + idx] = next;
                    prev = next;
                }
            }
        }
        bh.consume(dst);
    }

    @Benchmark
    public void undeltaShift(Blackhole bh) {
        int lanes = 16;
        int typeBits = 64;
        for (int chunk = 0; chunk < numChunks; chunk++) {
            int base = chunk * CHUNK;
            for (int lane = 0; lane < lanes; lane++) {
                long prev = src[base + lane];
                for (int row = 0; row < typeBits; row++) {
                    int o = row >> 3;
                    int s = row & 7;
                    int idx = ORDER[o] * 16 + (s << 7) + lane;
                    long next = src[base + idx] + prev;
                    dst[base + idx] = next;
                    prev = next;
                }
            }
        }
        bh.consume(dst);
    }

    @Benchmark
    public void undeltaTable(Blackhole bh) {
        int lanes = 16;
        int typeBits = 64;
        for (int chunk = 0; chunk < numChunks; chunk++) {
            int base = chunk * CHUNK;
            for (int lane = 0; lane < lanes; lane++) {
                long prev = src[base + lane];
                for (int row = 0; row < typeBits; row++) {
                    int idx = ITERATE_BASE[row] + lane;
                    long next = src[base + idx] + prev;
                    dst[base + idx] = next;
                    prev = next;
                }
            }
        }
        bh.consume(dst);
    }
}
