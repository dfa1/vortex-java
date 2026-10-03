package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
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

/// Widen (`toLongs`) and narrow (`fromLongsArray`) over one 64K-row chunk — the conversions
/// every cascade candidate (RLE, bit-packing, FoR, RunEnd, Patched) runs on the same data. The
/// question is whether C2 auto-vectorizes them: a scalar loop runs near one element per cycle,
/// a vectorized one is bounded by the output bandwidth (8 bytes per row written for widen).
///
/// Run: ./bench PrimitiveArraysBenchmark.widen
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class PrimitiveArraysBenchmark {

    /// One writer chunk; the cascade widens it once per candidate.
    private static final int ROWS = 65_536;

    @Param({"I8", "U8", "I32", "U32"})
    public PType ptype;

    private Object narrowData;
    private long[] wideData;

    @Setup
    public void setup() {
        Random random = new Random(42);
        wideData = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            wideData[i] = random.nextLong();
        }
        narrowData = PrimitiveArrays.fromLongsArray(wideData, ptype, EncodingId.VORTEX_PRIMITIVE);
        wideData = PrimitiveArrays.toLongs(narrowData, ptype, EncodingId.VORTEX_PRIMITIVE);
    }

    /// `PrimitiveArrays.toLongs`: narrow carrier -> `long[]`.
    ///
    /// @return the widened values (returned so JMH keeps the work)
    @Benchmark
    public long[] widen() {
        return PrimitiveArrays.toLongs(narrowData, ptype, EncodingId.VORTEX_PRIMITIVE);
    }

    /// `PrimitiveArrays.fromLongsArray`: `long[]` -> narrow carrier.
    ///
    /// @return the narrowed carrier (returned so JMH keeps the work)
    @Benchmark
    public Object narrow() {
        return PrimitiveArrays.fromLongsArray(wideData, ptype, EncodingId.VORTEX_PRIMITIVE);
    }
}
