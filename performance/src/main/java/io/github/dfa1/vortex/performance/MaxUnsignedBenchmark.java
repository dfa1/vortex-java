package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.simd.SimdOperations;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// [SimdOperations#maxUnsigned(MemorySegment, long, PType)] (the dictionary code bound check) against
/// the long-domain loop it replaced, over 1M codes. Run `./bench MaxUnsignedBenchmark.kernel` and
/// `./bench MaxUnsignedBenchmark.longDomain`, `-f 3` for numbers worth recording.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MaxUnsignedBenchmark {

    private static final int N = 1 << 20;

    @Param({"U8", "U16", "U32"})
    public PType ptype;

    private final SimdOperations ops = SimdOperationsSupport.preferred();
    private Arena arena;
    private MemorySegment codes;

    @Setup
    public void setup() {
        arena = Arena.ofConfined();
        codes = arena.allocate((long) N * ptype.byteSize(), ptype.byteSize());
        Random random = new Random(42);
        for (long i = 0; i < codes.byteSize(); i++) {
            codes.set(ValueLayout.JAVA_BYTE, i, (byte) random.nextInt());
        }
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    /// The `int`-domain kernel.
    ///
    /// @return the maximum code
    @Benchmark
    public long kernel() {
        return ops.maxUnsigned(codes, N, ptype);
    }

    /// The previous shape: each code zero-extended to `long` and folded with a `long` max.
    ///
    /// @return the maximum code
    @Benchmark
    public long longDomain() {
        long max = 0;
        switch (ptype) {
            case U8 -> {
                for (long i = 0; i < N; i++) {
                    max = Math.max(max, Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i)));
                }
            }
            case U16 -> {
                for (long i = 0; i < N; i++) {
                    max = Math.max(max, Short.toUnsignedLong(codes.getAtIndex(VortexFormat.LE_SHORT, i)));
                }
            }
            default -> {
                for (long i = 0; i < N; i++) {
                    max = Math.max(max, Integer.toUnsignedLong(codes.getAtIndex(VortexFormat.LE_INT, i)));
                }
            }
        }
        return max;
    }
}
