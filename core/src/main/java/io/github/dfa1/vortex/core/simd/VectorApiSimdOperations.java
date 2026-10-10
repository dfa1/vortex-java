package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.model.PType;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.util.OptionalLong;

/// The [SimdOperations] written with the incubating Vector API, for the kernels where an explicit
/// vector loop is worth having over C2's auto-vectorization. Every other kernel delegates to the
/// [AutoVectorizedSimdOperations] it is built on, so each kernel is switched on independently,
/// by what the benchmarks show.
///
/// Only [SimdOperationsSupport] instantiates it, and only when `jdk.incubator.vector` is on the
/// module graph (`--add-modules jdk.incubator.vector`); without it this class fails to link and the
/// auto-vectorized implementation stays in effect. The contract is identical results for every
/// input, enforced by a differential test against the auto-vectorized implementation.
///
/// The shape follows Hardwood's `VectorOperations` (Apache-2.0, hardwood-hq/hardwood): the
/// preferred species of the CPU, a main loop over whole vectors, and a scalar tail for the rest.
final class VectorApiSimdOperations implements SimdOperations {

    private static final VectorSpecies<Byte> BYTES = ByteVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Short> SHORTS = ShortVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;

    private final SimdOperations fallback;

    VectorApiSimdOperations(SimdOperations fallback) {
        this.fallback = fallback;
    }

    /// Whether the Vector API is worth using on this CPU: a preferred vector of at least 128 bits.
    /// Anything narrower cannot beat the scalar loops.
    ///
    /// @return `true` if the preferred species holds at least four ints and sixteen bytes
    static boolean isUsable() {
        return INTS.length() >= 4 && BYTES.length() >= 16;
    }

    /// The width of the vectors in use, for diagnostics.
    ///
    /// @return the preferred vector size in bits
    static int vectorBitSize() {
        return INTS.vectorBitSize();
    }

    @Override
    public void widenInto(MemorySegment src, long fromElement, int count, PType ptype, long[] out) {
        fallback.widenInto(src, fromElement, count, ptype, out);
    }

    @Override
    public void widenArrayInto(Object values, int from, int count, PType ptype, long[] out) {
        fallback.widenArrayInto(values, from, count, ptype, out);
    }

    @Override
    public void narrowInto(long[] values, PType ptype, MemorySegment dst) {
        fallback.narrowInto(values, ptype, dst);
    }

    @Override
    public void narrowArrayInto(long[] values, PType ptype, Object out) {
        fallback.narrowArrayInto(values, ptype, out);
    }

    @Override
    public long maxUnsigned(MemorySegment src, long count, PType ptype) {
        return fallback.maxUnsigned(src, count, ptype);
    }

    // Lane-wise min and max accumulators, reduced once at the end. Unsigned widths XOR the sign bit
    // first, which maps unsigned order onto signed order so the loop stays a plain signed min/max.
    // Wins over C2 (128-bit NEON, 262144 elements, JMH -f 2): bytes 12x, shorts 5.6-5.9x, U32 2.8x
    // (C2's sign-flipped int loop is 3x slower than its plain one). I32 is 0.92x and I64 1.0x of C2,
    // so those delegate.
    @Override
    public long[] minMax(Object values, PType ptype) {
        if (Array.getLength(values) == 0) {
            throw new IllegalArgumentException("empty array has no min/max");
        }
        return switch (ptype) {
            case I8 -> minMax((byte[]) values, (byte) 0);
            case U8 -> unsigned(minMax((byte[]) values, Byte.MIN_VALUE), 0xFFL, Byte.MIN_VALUE);
            case I16 -> minMax((short[]) values, (short) 0);
            case U16 -> unsigned(minMax((short[]) values, Short.MIN_VALUE), 0xFFFFL, Short.MIN_VALUE);
            case U32 -> unsigned(minMax((int[]) values, Integer.MIN_VALUE), 0xFFFF_FFFFL, Integer.MIN_VALUE);
            case I32, I64, U64 -> fallback.minMax(values, ptype);
            case F16, F32, F64 -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
        };
    }

    /// Undoes the sign-bit flip of a minimum and maximum found in flipped space.
    private static long[] unsigned(long[] flipped, long mask, long signBit) {
        return new long[]{(flipped[0] ^ signBit) & mask, (flipped[1] ^ signBit) & mask};
    }

    private static long[] minMax(byte[] a, byte flip) {
        ByteVector flipVector = ByteVector.broadcast(BYTES, flip);
        ByteVector min = ByteVector.broadcast(BYTES, Byte.MAX_VALUE);
        ByteVector max = ByteVector.broadcast(BYTES, Byte.MIN_VALUE);
        int i = 0;
        for (; i + BYTES.length() <= a.length; i += BYTES.length()) {
            ByteVector v = ByteVector.fromArray(BYTES, a, i).lanewise(VectorOperators.XOR, flipVector);
            min = min.min(v);
            max = max.max(v);
        }
        int lo = min.reduceLanes(VectorOperators.MIN);
        int hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            int v = (byte) (a[i] ^ flip);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new long[]{lo, hi};
    }

    private static long[] minMax(short[] a, short flip) {
        ShortVector flipVector = ShortVector.broadcast(SHORTS, flip);
        ShortVector min = ShortVector.broadcast(SHORTS, Short.MAX_VALUE);
        ShortVector max = ShortVector.broadcast(SHORTS, Short.MIN_VALUE);
        int i = 0;
        for (; i + SHORTS.length() <= a.length; i += SHORTS.length()) {
            ShortVector v = ShortVector.fromArray(SHORTS, a, i).lanewise(VectorOperators.XOR, flipVector);
            min = min.min(v);
            max = max.max(v);
        }
        int lo = min.reduceLanes(VectorOperators.MIN);
        int hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            int v = (short) (a[i] ^ flip);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new long[]{lo, hi};
    }

    private static long[] minMax(int[] a, int flip) {
        IntVector flipVector = IntVector.broadcast(INTS, flip);
        IntVector min = IntVector.broadcast(INTS, Integer.MAX_VALUE);
        IntVector max = IntVector.broadcast(INTS, Integer.MIN_VALUE);
        int i = 0;
        for (; i + INTS.length() <= a.length; i += INTS.length()) {
            IntVector v = IntVector.fromArray(INTS, a, i).lanewise(VectorOperators.XOR, flipVector);
            min = min.min(v);
            max = max.max(v);
        }
        int lo = min.reduceLanes(VectorOperators.MIN);
        int hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            int v = a[i] ^ flip;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new long[]{lo, hi};
    }

    // Each vector is compared with the first element broadcast. Only the 1- and 2-byte carriers: C2
    // does not vectorize their widening compare, and this wins 5.3x (bytes) and 2.6x (shorts) over
    // it, while C2 vectorizes the 4-byte case so well (11.7 vs 35.1 us) that this loses 3x, and
    // 8-byte lanes lose 1.5x (128-bit NEON; 262144 elements, JMH -f 2).
    @Override
    public boolean allEqual(Object values, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> Array.getLength(values) == 0 || allEqual((byte[]) values);
            case I16, U16, F16 -> Array.getLength(values) == 0 || allEqual((short[]) values);
            case I32, U32, I64, U64, F32, F64 -> fallback.allEqual(values, ptype);
        };
    }

    // Same win as the bytes (3.4x over C2 on 262144 flags).
    @Override
    public boolean allEqual(boolean[] values) {
        if (values.length == 0) {
            return true;
        }
        ByteVector first = ByteVector.broadcast(BYTES, (byte) (values[0] ? 1 : 0));
        int i = 0;
        for (; i + BYTES.length() <= values.length; i += BYTES.length()) {
            if (ByteVector.fromBooleanArray(BYTES, values, i).compare(VectorOperators.NE, first).anyTrue()) {
                return false;
            }
        }
        for (; i < values.length; i++) {
            if (values[i] != values[0]) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqual(byte[] a) {
        ByteVector first = ByteVector.broadcast(BYTES, a[0]);
        int i = 0;
        for (; i + BYTES.length() <= a.length; i += BYTES.length()) {
            if (ByteVector.fromArray(BYTES, a, i).compare(VectorOperators.NE, first).anyTrue()) {
                return false;
            }
        }
        for (; i < a.length; i++) {
            if (a[i] != a[0]) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqual(short[] a) {
        ShortVector first = ShortVector.broadcast(SHORTS, a[0]);
        int i = 0;
        for (; i + SHORTS.length() <= a.length; i += SHORTS.length()) {
            if (ShortVector.fromArray(SHORTS, a, i).compare(VectorOperators.NE, first).anyTrue()) {
                return false;
            }
        }
        for (; i < a.length; i++) {
            if (a[i] != a[0]) {
                return false;
            }
        }
        return true;
    }

    // Each vector is compared against the same elements shifted by one, so the changes are counted
    // without a dependency between iterations. Only the 1- and 2-byte carriers: C2 does not vectorize
    // their widening `long` accumulate, and this wins 3-6x (bytes) and 1.9-3x (shorts) over it, while
    // for 4- and 8-byte lanes C2 already vectorizes the scalar loop and this loses (0.7x ints,
    // 0.4x longs/doubles on 128-bit NEON; 262144 elements, JMH -f 2).
    @Override
    public long runs(Object values, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> Array.getLength(values) == 0 ? 0 : 1 + changes((byte[]) values);
            case I16, U16, F16 -> Array.getLength(values) == 0 ? 0 : 1 + changes((short[]) values);
            case I32, U32, I64, U64, F32, F64 -> fallback.runs(values, ptype);
        };
    }

    private static long changes(byte[] a) {
        long changes = 0;
        int i = 1;
        for (; i + BYTES.length() <= a.length; i += BYTES.length()) {
            changes += ByteVector.fromArray(BYTES, a, i)
                    .compare(VectorOperators.NE, ByteVector.fromArray(BYTES, a, i - 1)).trueCount();
        }
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    private static long changes(short[] a) {
        long changes = 0;
        int i = 1;
        for (; i + SHORTS.length() <= a.length; i += SHORTS.length()) {
            changes += ShortVector.fromArray(SHORTS, a, i)
                    .compare(VectorOperators.NE, ShortVector.fromArray(SHORTS, a, i - 1)).trueCount();
        }
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    @Override
    public OptionalLong sum(Object values, PType ptype) {
        return fallback.sum(values, ptype);
    }

    @Override
    public double sumFloating(Object values, PType ptype) {
        return fallback.sumFloating(values, ptype);
    }

    @Override
    public void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        fallback.undeltaChunk(deltas, bases, lanes, typeBits, mask, out);
    }

    @Override
    public void deltaChunk(long[] values, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        fallback.deltaChunk(values, bases, lanes, typeBits, mask, out);
    }

    @Override
    public void packBlock(long[] values, int offset, int bitWidth, int typeBits, long[] words) {
        fallback.packBlock(values, offset, bitWidth, typeBits, words);
    }
}
