package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.simd.SimdOperations;
import io.github.dfa1.vortex.core.simd.VectorSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Per-kernel micro-benchmarks for [SimdOperations]: one op processes 256 FastLanes chunks
/// (262144 elements) so a kernel that vectorizes shows up against one that does not. Run
/// `./bench SimdKernelBenchmark.undelta` (or `.delta`), `-f 3` for numbers worth recording.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SimdKernelBenchmark {

    private static final int CHUNKS = 256;

    @Param({"8", "16", "32", "64"})
    public int typeBits;

    private final SimdOperations ops = VectorSupport.operations();
    private long[] input;
    private long[] bases;
    private long[] out;
    private long[] words;
    private long mask;
    private int lanes;

    @Setup
    public void setup() {
        Random random = new Random(42);
        lanes = FastLanes.CHUNK / typeBits;
        mask = FastLanes.lowMask(typeBits);
        input = new long[FastLanes.CHUNK];
        for (int i = 0; i < input.length; i++) {
            input[i] = random.nextLong() & mask;
        }
        bases = new long[lanes];
        for (int i = 0; i < lanes; i++) {
            bases[i] = random.nextLong() & mask;
        }
        out = new long[FastLanes.CHUNK];
        words = new long[(typeBits / 2 - 1) * lanes];
    }

    /// `SimdOperations.undeltaChunk` over 256 chunks.
    ///
    /// @return the last chunk's output (returned so JMH keeps the work)
    @Benchmark
    public long[] undelta() {
        for (int c = 0; c < CHUNKS; c++) {
            ops.undeltaChunk(input, bases, lanes, typeBits, mask, out);
        }
        return out;
    }

    /// `SimdOperations.deltaChunk` over 256 chunks.
    ///
    /// @return the last chunk's output (returned so JMH keeps the work)
    @Benchmark
    public long[] delta() {
        for (int c = 0; c < CHUNKS; c++) {
            ops.deltaChunk(input, bases, lanes, typeBits, mask, out);
        }
        return out;
    }

    /// `SimdOperations.packBlock` over 256 blocks at `bitWidth = typeBits / 2 - 1`, so words straddle.
    ///
    /// @return the last block's words (returned so JMH keeps the work)
    @Benchmark
    public long[] pack() {
        int bitWidth = typeBits / 2 - 1;
        for (int c = 0; c < CHUNKS; c++) {
            ops.packBlock(input, 0, bitWidth, typeBits, words);
        }
        return words;
    }
}
