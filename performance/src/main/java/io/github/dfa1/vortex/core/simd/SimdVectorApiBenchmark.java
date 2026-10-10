package io.github.dfa1.vortex.core.simd;

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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Per-kernel A/B of the auto-vectorized [SimdOperations] against the Vector API one, in one JVM so
/// the two share a CPU state. Lives in the `core.simd` package to reach the package-private
/// implementations; the `impl` parameter picks which one a trial runs. One op processes `size`
/// elements. Run one kernel at a time, `-f 3` for numbers worth recording:
/// `./bench SimdVectorApiBenchmark.runs`.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED"})
public class SimdVectorApiBenchmark {

    @Param({"auto", "vector"})
    public String impl;

    private static final int CHUNKS = 256;

    @Param({"4096", "262144"})
    public int size;

    @Param({"I8", "I16", "I32", "I64", "F32", "F64"})
    public PType ptype;

    private SimdOperations ops;
    private Object runsFew;
    private Object runsMany;
    private Object constant;
    private boolean[] constantFlags;
    private MemorySegment codes;
    private long[] widened;
    private long[] wide;
    private Object narrowed;
    private MemorySegment narrowSegment;
    private long[] chunk;
    private long[] bases;
    private long[] chunkOut;
    private long[] words;
    private long chunkMask;
    private int lanes;
    private int typeBits;

    @Setup
    public void setup() {
        SimdOperations auto = new AutoVectorizedSimdOperations();
        ops = switch (impl) {
            case "auto" -> auto;
            case "vector" -> new VectorApiSimdOperations();
            default -> throw new IllegalArgumentException(impl);
        };
        runsMany = array(ptype, size, 1, new Random(1));
        runsFew = array(ptype, size, 64, new Random(2));
        constant = array(ptype, size, Integer.MAX_VALUE, new Random(3));
        constantFlags = new boolean[size];
        widened = new long[size];
        wide = new Random(5).longs(size).toArray();
        narrowed = switch (ptype) {
            case I8 -> new byte[size];
            case I16 -> new short[size];
            case I32 -> new int[size];
            case I64 -> new long[size];
            case F32 -> new float[size];
            case F64 -> new double[size];
            default -> null;
        };
        narrowSegment = Arena.ofAuto().allocate((long) size * 8);
        typeBits = ptype.byteSize() * 8;
        lanes = io.github.dfa1.vortex.core.compute.FastLanes.CHUNK / typeBits;
        chunkMask = io.github.dfa1.vortex.core.compute.FastLanes.lowMask(typeBits);
        Random fastLanes = new Random(6);
        chunk = new long[io.github.dfa1.vortex.core.compute.FastLanes.CHUNK * CHUNKS];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = fastLanes.nextLong() & chunkMask;
        }
        bases = new long[lanes];
        chunkOut = new long[io.github.dfa1.vortex.core.compute.FastLanes.CHUNK];
        words = new long[typeBits * lanes];
        codes = Arena.ofAuto().allocate((long) size * 8);
        Random fill = new Random(4);
        for (long b = 0; b < codes.byteSize(); b++) {
            codes.set(ValueLayout.JAVA_BYTE, b, (byte) fill.nextInt(256));
        }
    }

    /// Counts runs over data with no runs at all: every neighbor pair differs.
    @Benchmark
    public long runs_noRuns() {
        return ops.runs(runsMany, ptype);
    }

    /// Counts runs over data in runs of 64 equal values.
    @Benchmark
    public long runs_longRuns() {
        return ops.runs(runsFew, ptype);
    }

    /// The dictionary code bound check: the largest unsigned code (the I8/I16/I32 params run it as U8/U16/U32).
    @Benchmark
    public long maxUnsigned() {
        PType unsigned = switch (ptype) {
            case I8 -> PType.U8;
            case I16 -> PType.U16;
            case I32 -> PType.U32;
            case I64 -> PType.U64;
            default -> throw new IllegalArgumentException(ptype.toString());
        };
        return ops.maxUnsigned(codes, size, unsigned);
    }

    /// Sums random full-range integers into a long (I8, I16, I32; I64 keeps its per-add overflow check).
    @Benchmark
    public java.util.OptionalLong sum() {
        return ops.sum(runsMany, ptype);
    }

    /// Widens a heap array of random full-range integers to longs (I8, I16, I32).
    @Benchmark
    public long[] widenArray() {
        ops.widenArrayInto(runsMany, 0, size, ptype, widened);
        return widened;
    }

    /// Widens a segment of random full-range integers to longs, as the decoders do (I8, I16, I32).
    @Benchmark
    public long[] widenSegment() {
        ops.widenInto(codes, 0, size, ptype, widened);
        return widened;
    }

    /// Narrows random longs into a heap array of the target width (I8, I16, I32).
    @Benchmark
    public Object narrowArray() {
        ops.narrowArrayInto(wide, ptype, narrowed);
        return narrowed;
    }

    /// Narrows random longs into a segment, as the writer does (I8, I16, I32).
    @Benchmark
    public MemorySegment narrowSegment() {
        ops.narrowInto(wide, ptype, narrowSegment);
        return narrowSegment;
    }

    /// Undeltas 256 FastLanes chunks (I8, I16, I32, I64).
    @Benchmark
    public long[] undelta() {
        for (int c = 0; c < CHUNKS; c++) {
            ops.undeltaChunk(chunk, bases, lanes, typeBits, chunkMask, chunkOut);
        }
        return chunkOut;
    }

    /// Deltas 256 FastLanes chunks (I8, I16, I32, I64).
    @Benchmark
    public long[] delta() {
        for (int c = 0; c < CHUNKS; c++) {
            ops.deltaChunk(chunk, bases, lanes, typeBits, chunkMask, chunkOut);
        }
        return chunkOut;
    }

    /// Bit-packs 256 FastLanes blocks at half the type width (I8, I16, I32, I64).
    @Benchmark
    public long[] pack() {
        for (int c = 0; c < CHUNKS; c++) {
            ops.packBlock(chunk, c * 1024, typeBits / 2, typeBits, words);
        }
        return words;
    }

    /// Sums floats strictly left to right (the F64 param).
    @Benchmark
    public double sumFloating() {
        return ops.sumFloating(runsMany, ptype);
    }

    /// Finds the smallest and largest element of random full-range data (integer ptypes only).
    @Benchmark
    public long[] minMax() {
        return ops.minMax(runsMany, ptype);
    }

    /// Checks an all-equal array, the worst case for an early exit: every element is compared.
    @Benchmark
    public boolean allEqual_constant() {
        return ops.allEqual(constant, ptype);
    }

    /// Checks an all-equal flag array.
    @Benchmark
    public boolean allEqual_booleans() {
        return ops.allEqual(constantFlags);
    }

    private static Object array(PType ptype, int n, int runLength, Random random) {
        long[] base = new long[n];
        long current = 0;
        for (int i = 0; i < n; i++) {
            if (i % runLength == 0) {
                current = random.nextLong();
            }
            base[i] = current;
        }
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (byte) base[i];
                }
                yield a;
            }
            case I16, U16, F16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (short) base[i];
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (int) base[i];
                }
                yield a;
            }
            case I64, U64 -> base;
            case F32 -> {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = Float.intBitsToFloat((int) base[i]);
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[n];
                for (int i = 0; i < n; i++) {
                    a[i] = Double.longBitsToDouble(base[i]);
                }
                yield a;
            }
        };
    }
}
