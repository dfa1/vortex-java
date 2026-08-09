package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyRleByteArray;
import io.github.dfa1.vortex.reader.array.LazyRleIntArray;
import io.github.dfa1.vortex.reader.array.LazyRleLongArray;
import io.github.dfa1.vortex.reader.array.LazySparseDoubleArray;
import io.github.dfa1.vortex.reader.array.LazySparseFloatArray;
import io.github.dfa1.vortex.reader.array.LazySparseIntArray;
import io.github.dfa1.vortex.reader.array.LazySparseLongArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/// Microbenchmark for the `Lazy{Sparse,Rle}` `fold` / `forEach` walk paths.
///
/// Exercises every value-type whose walk was hoisted into the shared
/// `SparseArrays.walkPatches` / `RleArrays.walkRuns` helpers, all in one JVM so
/// the shared walker call sites see multiple lambda implementations — the
/// megamorphic condition that a regression (lost inlining / vectorization on
/// the fill / constant-run loops) would show up under. Compare results against
/// the pre-refactor inlined implementations on `main`.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class LazyArrayWalkBenchmark {

    private static final int ROWS = 1_000_000;
    private static final int NUM_CHUNKS = (ROWS + 1023) / 1024;
    private static final int SPARSE_PATCHES = 1_000;

    private static final DType I32 = DType.I32;
    private static final DType I64 = DType.I64;
    private static final DType I8 = DType.I8;
    private static final DType F64 = DType.F64;
    private static final DType F32 = DType.F32;

    private Arena arena;

    private LazySparseIntArray sparseInt;
    private LazySparseLongArray sparseLong;
    private LazySparseDoubleArray sparseDouble;
    private LazySparseFloatArray sparseFloat;

    private LazyRleIntArray rleIntConst;
    private LazyRleIntArray rleIntMulti;
    private LazyRleLongArray rleLongMulti;
    private LazyRleByteArray rleByteMulti;

    @Setup
    public void setup() {
        arena = Arena.ofShared();

        // Sparse: mostly fill, few patches — stresses the fill-emission walk.
        int[] patchIdx = new int[SPARSE_PATCHES];
        for (int k = 0; k < SPARSE_PATCHES; k++) {
            patchIdx[k] = k * (ROWS / SPARSE_PATCHES);
        }
        Array idxArr = intArray(patchIdx);
        sparseInt = new LazySparseIntArray(I32, ROWS, 7, intArrayValues(SPARSE_PATCHES), idxArr, 0L);
        sparseLong = new LazySparseLongArray(I64, ROWS, 7L, longArrayValues(SPARSE_PATCHES), idxArr, 0L);
        sparseDouble = new LazySparseDoubleArray(F64, ROWS, 1.5, doubleArrayValues(SPARSE_PATCHES), idxArr, 0L);
        sparseFloat = new LazySparseFloatArray(F32, ROWS, 1.5f, floatArrayValues(SPARSE_PATCHES), idxArr, 0L);

        // RLE constant runs: one distinct value per chunk — constant fast path.
        long[] constOffsets = new long[NUM_CHUNKS];
        MemorySegment constValuesI = arena.allocate(NUM_CHUNKS * 4L, 4);
        for (int c = 0; c < NUM_CHUNKS; c++) {
            constOffsets[c] = c;
            constValuesI.setAtIndex(VortexFormat.LE_INT, c, c);
        }
        MemorySegment constIndices = arena.allocate(NUM_CHUNKS * 1024L);
        rleIntConst = new LazyRleIntArray(I32, ROWS, constValuesI, constIndices, false,
                constOffsets, 0L, NUM_CHUNKS, NUM_CHUNKS, 0);

        // RLE multi-value: 4 distinct values per chunk — per-row emit path.
        int valsPerChunk = 4;
        long valuesLen = (long) NUM_CHUNKS * valsPerChunk;
        long[] multiOffsets = new long[NUM_CHUNKS];
        MemorySegment multiValuesI = arena.allocate(valuesLen * 4L, 4);
        MemorySegment multiValuesL = arena.allocate(valuesLen * 8L, 8);
        MemorySegment multiValuesB = arena.allocate(valuesLen);
        for (int c = 0; c < NUM_CHUNKS; c++) {
            multiOffsets[c] = (long) c * valsPerChunk;
            for (int j = 0; j < valsPerChunk; j++) {
                int slot = c * valsPerChunk + j;
                multiValuesI.setAtIndex(VortexFormat.LE_INT, slot, c * 10 + j);
                multiValuesL.setAtIndex(VortexFormat.LE_LONG, slot, c * 10L + j);
                multiValuesB.set(ValueLayout.JAVA_BYTE, slot, (byte) (c + j));
            }
        }
        // u8 index table: 4 runs per chunk fit a byte, the width the writer picks here.
        MemorySegment multiIndices = arena.allocate(NUM_CHUNKS * 1024L);
        for (long r = 0; r < multiIndices.byteSize(); r++) {
            multiIndices.set(ValueLayout.JAVA_BYTE, r, (byte) (r & (valsPerChunk - 1)));
        }
        rleIntMulti = new LazyRleIntArray(I32, ROWS, multiValuesI, multiIndices, false,
                multiOffsets, 0L, valuesLen, NUM_CHUNKS, 0);
        rleLongMulti = new LazyRleLongArray(I64, ROWS, multiValuesL, multiIndices, false,
                multiOffsets, 0L, valuesLen, NUM_CHUNKS, 0);
        rleByteMulti = new LazyRleByteArray(I8, ROWS, multiValuesB, multiIndices, false,
                multiOffsets, 0L, valuesLen, NUM_CHUNKS, 0, false);
    }

    @Benchmark
    public int sparseFoldInt() {
        return sparseInt.fold(0, Integer::sum);
    }

    @Benchmark
    public long sparseFoldLong() {
        return sparseLong.fold(0L, Long::sum);
    }

    @Benchmark
    public double sparseFoldDouble() {
        return sparseDouble.fold(0.0, Double::sum);
    }

    @Benchmark
    public double sparseFoldFloat() {
        return sparseFloat.fold(0.0, Double::sum);
    }

    @Benchmark
    public int rleFoldIntConst() {
        return rleIntConst.fold(0, Integer::sum);
    }

    @Benchmark
    public int rleFoldIntMulti() {
        return rleIntMulti.fold(0, Integer::sum);
    }

    @Benchmark
    public long rleFoldLongMulti() {
        return rleLongMulti.fold(0L, Long::sum);
    }

    @Benchmark
    public long rleFoldByteMulti() {
        return rleByteMulti.fold(0L, Long::sum);
    }

    private IntArray intArrayValues(int n) {
        int[] vs = new int[n];
        for (int i = 0; i < n; i++) {
            vs[i] = i;
        }
        return intArray(vs);
    }

    private IntArray intArray(int[] vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, vs[i]);
        }
        return new MaterializedIntArray(I32, vs.length, seg.asReadOnly());
    }

    private LongArray longArrayValues(int n) {
        MemorySegment seg = arena.allocate(n * 8L, 8);
        for (int i = 0; i < n; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, i);
        }
        return new MaterializedLongArray(I64, n, seg.asReadOnly());
    }

    private DoubleArray doubleArrayValues(int n) {
        MemorySegment seg = arena.allocate(n * 8L, 8);
        for (int i = 0; i < n; i++) {
            seg.setAtIndex(ValueLayout.JAVA_DOUBLE, i, i + 0.25);
        }
        return new MaterializedDoubleArray(F64, n, seg.asReadOnly());
    }

    private FloatArray floatArrayValues(int n) {
        MemorySegment seg = arena.allocate(n * 4L, 4);
        for (int i = 0; i < n; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, i + 0.25f);
        }
        return new MaterializedFloatArray(F32, n, seg.asReadOnly());
    }
}
