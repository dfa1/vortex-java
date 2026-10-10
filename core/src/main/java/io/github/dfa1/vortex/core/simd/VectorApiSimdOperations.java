package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.PType;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.util.OptionalLong;

/// The [SimdOperations] written with the incubating Vector API: explicit vector loops over the
/// preferred species of the CPU, each with a scalar tail for the elements that do not fill a vector.
/// The Vector API does the work, including the reductions; no kernel is forced back to a scalar loop
/// to protect an ordering, with the two exceptions below.
///
/// Only [SimdOperationsSupport] instantiates it, and only when `jdk.incubator.vector` is on the
/// module graph (`--add-modules jdk.incubator.vector`); without it this class fails to link and the
/// [AutoVectorizedSimdOperations] stays in effect. A differential test pins every kernel to that
/// implementation, which follows the Rust reference.
///
/// ## Where it differs from Rust (a deliberate decision)
///
/// - **Floating-point sums** accumulate lane-wise and reduce once, so they add in a different order
///   from Rust's sequential `f64` sum. The result can differ in its last bits, and the JDK documents
///   `reduceLanes(ADD)` on floats as using "an arbitrary order of operations, which may even vary over
///   time", so it may also differ between runs and between CPUs with different vector widths. Integer
///   results are exact.
/// - **`I64`/`U64` sums** detect overflow on each lane's running sum, not on the sequential prefix Rust
///   checks (a checked add per element). Both can report an overflow the other does not, on arrays whose
///   partial sums approach the limits: for example `[MAX, 1, -1]` overflows Rust's prefix but not the
///   total.
/// - **`F16`** sums and min/max are scalar loops: the Vector API has no half-float lanes.
///
/// Outputs derived from these (the zone-map sum statistic) therefore depend on whether the module is
/// enabled; the auto-vectorized implementation, used without the flag, is the Rust-parity one. See
/// `docs/compatibility.md`.
///
/// The shape follows Hardwood's `VectorOperations` (Apache-2.0, hardwood-hq/hardwood): the
/// preferred species, a main loop over whole vectors, and a scalar tail.
final class VectorApiSimdOperations implements SimdOperations {

    private static final VectorSpecies<Byte> BYTES = ByteVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Short> SHORTS = ShortVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Long> LONGS = LongVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Float> FLOATS = FloatVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Double> DOUBLES = DoubleVector.SPECIES_PREFERRED;

    /// Vector iterations a narrow-element sum accumulates in `int` lanes before flushing to a `long`.
    /// A lane gains at most `parts` elements per iteration, each below 2^16, so 2^13 iterations stay
    /// far under 2^31.
    private static final int SUM_FLUSH_ITERATIONS = 1 << 13;

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

    // ---- widen ----

    // Narrow lanes widen in stages (byte to int to long, short to int to long): the Vector API does not
    // intrinsify a direct conversion across a lane ratio of four or more into 64-bit lanes, and that
    // direct form measured about 40x slower than the staged one (JMH, 128-bit NEON). The ratios of the
    // stages (4 and 2, 2 and 2) are fixed by the types, so they hold at any vector width.
    @Override
    public void widenInto(MemorySegment src, long fromElement, int count, PType ptype, long[] out) {
        switch (ptype) {
            case I8, U8 -> {
                int i = 0;
                for (; i + BYTES.length() <= count; i += BYTES.length()) {
                    widenBytes(ByteVector.fromMemorySegment(BYTES, src, fromElement + i, ByteOrder.LITTLE_ENDIAN),
                            ptype == PType.U8, out, i);
                }
                for (; i < count; i++) {
                    long v = src.get(ValueLayout.JAVA_BYTE, fromElement + i);
                    out[i] = ptype == PType.U8 ? v & 0xFFL : v;
                }
            }
            case I16, U16, F16 -> {
                int i = 0;
                for (; i + SHORTS.length() <= count; i += SHORTS.length()) {
                    widenShorts(ShortVector.fromMemorySegment(SHORTS, src, (fromElement + i) * 2, ByteOrder.LITTLE_ENDIAN),
                            ptype != PType.I16, out, i);
                }
                for (; i < count; i++) {
                    long v = src.getAtIndex(VortexFormat.LE_SHORT, fromElement + i);
                    out[i] = ptype == PType.I16 ? v : v & 0xFFFFL;
                }
            }
            case I32, U32, F32 -> {
                int i = 0;
                for (; i + INTS.length() <= count; i += INTS.length()) {
                    widenInts(IntVector.fromMemorySegment(INTS, src, (fromElement + i) * 4, ByteOrder.LITTLE_ENDIAN),
                            ptype != PType.I32, out, i);
                }
                for (; i < count; i++) {
                    long v = src.getAtIndex(VortexFormat.LE_INT, fromElement + i);
                    out[i] = ptype == PType.I32 ? v : v & 0xFFFF_FFFFL;
                }
            }
            // A copy is a copy: the JDK's bulk copy is what a vector loop would be at best
            case I64, U64, F64 -> MemorySegment.copy(src, VortexFormat.LE_LONG, fromElement * 8, out, 0, count);
        }
    }

    @Override
    public void widenArrayInto(Object values, int from, int count, PType ptype, long[] out) {
        switch (ptype) {
            case I8, U8 -> {
                byte[] a = (byte[]) values;
                int i = 0;
                for (; i + BYTES.length() <= count; i += BYTES.length()) {
                    widenBytes(ByteVector.fromArray(BYTES, a, from + i), ptype == PType.U8, out, i);
                }
                for (; i < count; i++) {
                    out[i] = ptype == PType.U8 ? a[from + i] & 0xFFL : a[from + i];
                }
            }
            case I16, U16, F16 -> {
                short[] a = (short[]) values;
                int i = 0;
                for (; i + SHORTS.length() <= count; i += SHORTS.length()) {
                    widenShorts(ShortVector.fromArray(SHORTS, a, from + i), ptype != PType.I16, out, i);
                }
                for (; i < count; i++) {
                    out[i] = ptype == PType.I16 ? a[from + i] : a[from + i] & 0xFFFFL;
                }
            }
            case I32, U32 -> {
                int[] a = (int[]) values;
                int i = 0;
                for (; i + INTS.length() <= count; i += INTS.length()) {
                    widenInts(IntVector.fromArray(INTS, a, from + i), ptype == PType.U32, out, i);
                }
                for (; i < count; i++) {
                    out[i] = ptype == PType.U32 ? a[from + i] & 0xFFFF_FFFFL : a[from + i];
                }
            }
            case I64, U64 -> System.arraycopy((long[]) values, from, out, 0, count);
            case F32 -> {
                float[] a = (float[]) values;
                int i = 0;
                for (; i + FLOATS.length() <= count; i += FLOATS.length()) {
                    widenInts(FloatVector.fromArray(FLOATS, a, from + i).reinterpretAsInts(), true, out, i);
                }
                for (; i < count; i++) {
                    out[i] = Float.floatToRawIntBits(a[from + i]) & 0xFFFF_FFFFL;
                }
            }
            case F64 -> {
                double[] a = (double[]) values;
                int i = 0;
                for (; i + DOUBLES.length() <= count; i += DOUBLES.length()) {
                    DoubleVector.fromArray(DOUBLES, a, from + i).reinterpretAsLongs().intoArray(out, i);
                }
                for (; i < count; i++) {
                    out[i] = Double.doubleToRawLongBits(a[from + i]);
                }
            }
        }
    }

    /// Widens a byte vector to `BYTES.length()` longs at `out[at]`: byte to int (four parts), int to long.
    private static void widenBytes(ByteVector v, boolean unsigned, long[] out, int at) {
        VectorOperators.Conversion<Byte, Integer> toInt = unsigned ? VectorOperators.ZERO_EXTEND_B2I : VectorOperators.B2I;
        for (int part = 0; part < BYTES.length() / INTS.length(); part++) {
            widenInts((IntVector) v.convertShape(toInt, INTS, part), false, out, at + part * INTS.length());
        }
    }

    private static void widenShorts(ShortVector v, boolean unsigned, long[] out, int at) {
        VectorOperators.Conversion<Short, Integer> toInt = unsigned ? VectorOperators.ZERO_EXTEND_S2I : VectorOperators.S2I;
        for (int part = 0; part < SHORTS.length() / INTS.length(); part++) {
            widenInts((IntVector) v.convertShape(toInt, INTS, part), false, out, at + part * INTS.length());
        }
    }

    /// Widens an int vector to `INTS.length()` longs at `out[at]`, sign- or zero-extending the lanes.
    private static void widenInts(IntVector v, boolean unsigned, long[] out, int at) {
        VectorOperators.Conversion<Integer, Long> toLong = unsigned ? VectorOperators.ZERO_EXTEND_I2L : VectorOperators.I2L;
        for (int part = 0; part < INTS.length() / LONGS.length(); part++) {
            ((LongVector) v.convertShape(toLong, LONGS, part)).intoArray(out, at + part * LONGS.length());
        }
    }

    // ---- narrow ----

    @Override
    public void narrowInto(long[] values, PType ptype, MemorySegment dst) {
        int n = values.length;
        switch (ptype) {
            case I8, U8 -> {
                int i = 0;
                for (; i + BYTES.length() <= n; i += BYTES.length()) {
                    narrowToBytes(values, i).intoMemorySegment(dst, i, ByteOrder.LITTLE_ENDIAN);
                }
                for (; i < n; i++) {
                    dst.set(ValueLayout.JAVA_BYTE, i, (byte) values[i]);
                }
            }
            case I16, U16, F16 -> {
                int i = 0;
                for (; i + SHORTS.length() <= n; i += SHORTS.length()) {
                    narrowToShorts(values, i).intoMemorySegment(dst, i * 2L, ByteOrder.LITTLE_ENDIAN);
                }
                for (; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i, (short) values[i]);
                }
            }
            case I32, U32, F32 -> {
                int i = 0;
                for (; i + INTS.length() <= n; i += INTS.length()) {
                    narrowToInts(values, i).intoMemorySegment(dst, i * 4L, ByteOrder.LITTLE_ENDIAN);
                }
                for (; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_INT, i, (int) values[i]);
                }
            }
            case I64, U64, F64 -> MemorySegment.copy(values, 0, dst, VortexFormat.LE_LONG, 0, n);
        }
    }

    @Override
    public void narrowArrayInto(long[] values, PType ptype, Object out) {
        int n = values.length;
        switch (ptype) {
            case I8, U8 -> {
                byte[] a = (byte[]) out;
                int i = 0;
                for (; i + BYTES.length() <= n; i += BYTES.length()) {
                    narrowToBytes(values, i).intoArray(a, i);
                }
                for (; i < n; i++) {
                    a[i] = (byte) values[i];
                }
            }
            case I16, U16, F16 -> {
                short[] a = (short[]) out;
                int i = 0;
                for (; i + SHORTS.length() <= n; i += SHORTS.length()) {
                    narrowToShorts(values, i).intoArray(a, i);
                }
                for (; i < n; i++) {
                    a[i] = (short) values[i];
                }
            }
            case I32, U32 -> {
                int[] a = (int[]) out;
                int i = 0;
                for (; i + INTS.length() <= n; i += INTS.length()) {
                    narrowToInts(values, i).intoArray(a, i);
                }
                for (; i < n; i++) {
                    a[i] = (int) values[i];
                }
            }
            case I64, U64 -> System.arraycopy(values, 0, (long[]) out, 0, n);
            case F32 -> {
                float[] a = (float[]) out;
                int i = 0;
                for (; i + INTS.length() <= n; i += INTS.length()) {
                    narrowToInts(values, i).reinterpretAsFloats().intoArray(a, i);
                }
                for (; i < n; i++) {
                    a[i] = Float.intBitsToFloat((int) values[i]);
                }
            }
            case F64 -> {
                double[] a = (double[]) out;
                int i = 0;
                for (; i + LONGS.length() <= n; i += LONGS.length()) {
                    LongVector.fromArray(LONGS, values, i).reinterpretAsDoubles().intoArray(a, i);
                }
                for (; i < n; i++) {
                    a[i] = Double.longBitsToDouble(values[i]);
                }
            }
        }
    }

    // Narrowing mirrors widening: two long vectors contract into one int vector (parts 0 and -1), and
    // 4 (bytes) or 2 (shorts) int vectors contract into one byte or short vector, each into its own slice
    // of the result (part -k) with the other lanes zero, combined with an OR. A direct long to byte
    // contraction measured about 25x slower than this staged form.
    private static IntVector narrowToInts(long[] values, int from) {
        return ((IntVector) LongVector.fromArray(LONGS, values, from).convertShape(VectorOperators.L2I, INTS, 0))
                .or((IntVector) LongVector.fromArray(LONGS, values, from + LONGS.length())
                        .convertShape(VectorOperators.L2I, INTS, -1));
    }

    private static ByteVector narrowToBytes(long[] values, int from) {
        ByteVector result = ByteVector.zero(BYTES);
        for (int part = 0; part < BYTES.length() / INTS.length(); part++) {
            result = result.or((ByteVector) narrowToInts(values, from + part * INTS.length())
                    .convertShape(VectorOperators.I2B, BYTES, -part));
        }
        return result;
    }

    private static ShortVector narrowToShorts(long[] values, int from) {
        ShortVector result = ShortVector.zero(SHORTS);
        for (int part = 0; part < SHORTS.length() / INTS.length(); part++) {
            result = result.or((ShortVector) narrowToInts(values, from + part * INTS.length())
                    .convertShape(VectorOperators.I2S, SHORTS, -part));
        }
        return result;
    }

    // ---- maxUnsigned ----

    // The sign bit is flipped so the signed lane max orders unsigned values, and flipped back at the end.
    @Override
    public long maxUnsigned(MemorySegment src, long count, PType ptype) {
        return switch (ptype) {
            case U8 -> maxUnsignedBytes(src, count);
            case U16 -> maxUnsignedShorts(src, count);
            case U32 -> maxUnsignedInts(src, count);
            case U64 -> maxUnsignedLongs(src, count);
            default -> throw new IllegalArgumentException("not an unsigned integer ptype: " + ptype);
        };
    }

    private static long maxUnsignedBytes(MemorySegment src, long count) {
        ByteVector flip = ByteVector.broadcast(BYTES, Byte.MIN_VALUE);
        ByteVector max = ByteVector.broadcast(BYTES, Byte.MIN_VALUE);
        long i = 0;
        for (; i + BYTES.length() <= count; i += BYTES.length()) {
            max = max.max(ByteVector.fromMemorySegment(BYTES, src, i, ByteOrder.LITTLE_ENDIAN)
                    .lanewise(VectorOperators.XOR, flip));
        }
        int result = max.reduceLanes(VectorOperators.MAX);
        for (; i < count; i++) {
            result = Math.max(result, (byte) (src.get(ValueLayout.JAVA_BYTE, i) ^ Byte.MIN_VALUE));
        }
        return Byte.toUnsignedLong((byte) (result ^ Byte.MIN_VALUE));
    }

    private static long maxUnsignedShorts(MemorySegment src, long count) {
        ShortVector flip = ShortVector.broadcast(SHORTS, Short.MIN_VALUE);
        ShortVector max = ShortVector.broadcast(SHORTS, Short.MIN_VALUE);
        long i = 0;
        for (; i + SHORTS.length() <= count; i += SHORTS.length()) {
            max = max.max(ShortVector.fromMemorySegment(SHORTS, src, i * 2, ByteOrder.LITTLE_ENDIAN)
                    .lanewise(VectorOperators.XOR, flip));
        }
        int result = max.reduceLanes(VectorOperators.MAX);
        for (; i < count; i++) {
            result = Math.max(result, (short) (src.getAtIndex(VortexFormat.LE_SHORT, i) ^ Short.MIN_VALUE));
        }
        return Short.toUnsignedLong((short) (result ^ Short.MIN_VALUE));
    }

    private static long maxUnsignedInts(MemorySegment src, long count) {
        IntVector flip = IntVector.broadcast(INTS, Integer.MIN_VALUE);
        IntVector max = IntVector.broadcast(INTS, Integer.MIN_VALUE);
        long i = 0;
        for (; i + INTS.length() <= count; i += INTS.length()) {
            max = max.max(IntVector.fromMemorySegment(INTS, src, i * 4, ByteOrder.LITTLE_ENDIAN)
                    .lanewise(VectorOperators.XOR, flip));
        }
        int result = max.reduceLanes(VectorOperators.MAX);
        for (; i < count; i++) {
            result = Math.max(result, src.getAtIndex(VortexFormat.LE_INT, i) ^ Integer.MIN_VALUE);
        }
        return Integer.toUnsignedLong(result ^ Integer.MIN_VALUE);
    }

    private static long maxUnsignedLongs(MemorySegment src, long count) {
        LongVector flip = LongVector.broadcast(LONGS, Long.MIN_VALUE);
        LongVector max = LongVector.broadcast(LONGS, Long.MIN_VALUE);
        long i = 0;
        for (; i + LONGS.length() <= count; i += LONGS.length()) {
            max = max.max(LongVector.fromMemorySegment(LONGS, src, i * 8, ByteOrder.LITTLE_ENDIAN)
                    .lanewise(VectorOperators.XOR, flip));
        }
        long result = max.reduceLanes(VectorOperators.MAX);
        for (; i < count; i++) {
            result = Math.max(result, src.getAtIndex(VortexFormat.LE_LONG, i) ^ Long.MIN_VALUE);
        }
        return result ^ Long.MIN_VALUE;
    }

    // ---- minMax ----

    // Lane-wise min and max accumulators, reduced once at the end. Unsigned widths XOR the sign bit
    // first, which maps unsigned order onto signed order so the loop stays a plain signed min/max.
    @Override
    public long[] minMax(Object values, PType ptype) {
        if (Array.getLength(values) == 0) {
            if (ptype.isFloating()) {
                return new long[0];
            }
            throw new IllegalArgumentException("empty array has no min/max");
        }
        return switch (ptype) {
            case I8 -> minMax((byte[]) values, (byte) 0);
            case U8 -> unsigned(minMax((byte[]) values, Byte.MIN_VALUE), 0xFFL, Byte.MIN_VALUE);
            case I16 -> minMax((short[]) values, (short) 0);
            case U16 -> unsigned(minMax((short[]) values, Short.MIN_VALUE), 0xFFFFL, Short.MIN_VALUE);
            case I32 -> minMax((int[]) values, 0);
            case U32 -> unsigned(minMax((int[]) values, Integer.MIN_VALUE), 0xFFFF_FFFFL, Integer.MIN_VALUE);
            case I64 -> minMax((long[]) values, 0L);
            case U64 -> unsigned(minMax((long[]) values, Long.MIN_VALUE), -1L, Long.MIN_VALUE);
            case F16 -> minMaxHalf((short[]) values);
            case F32 -> minMax((float[]) values);
            case F64 -> minMax((double[]) values);
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

    private static long[] minMax(long[] a, long flip) {
        LongVector flipVector = LongVector.broadcast(LONGS, flip);
        LongVector min = LongVector.broadcast(LONGS, Long.MAX_VALUE);
        LongVector max = LongVector.broadcast(LONGS, Long.MIN_VALUE);
        int i = 0;
        for (; i + LONGS.length() <= a.length; i += LONGS.length()) {
            LongVector v = LongVector.fromArray(LONGS, a, i).lanewise(VectorOperators.XOR, flipVector);
            min = min.min(v);
            max = max.max(v);
        }
        long lo = min.reduceLanes(VectorOperators.MIN);
        long hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            long v = a[i] ^ flip;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new long[]{lo, hi};
    }

    // Masked lane-wise min/max: a NaN lane is left out of the reduction (Rust's `skip_nans`), and the
    // lane-wise and cross-lane min/max follow Math.min/Math.max, which order -0.0 before 0.0 exactly as
    // Rust's total order does.
    private static long[] minMax(float[] a) {
        FloatVector min = FloatVector.broadcast(FLOATS, Float.POSITIVE_INFINITY);
        FloatVector max = FloatVector.broadcast(FLOATS, Float.NEGATIVE_INFINITY);
        int i = 0;
        for (; i + FLOATS.length() <= a.length; i += FLOATS.length()) {
            FloatVector v = FloatVector.fromArray(FLOATS, a, i);
            VectorMask<Float> ordered = v.test(VectorOperators.IS_NAN).not();
            min = min.lanewise(VectorOperators.MIN, v, ordered);
            max = max.lanewise(VectorOperators.MAX, v, ordered);
        }
        float lo = min.reduceLanes(VectorOperators.MIN);
        float hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            if (a[i] == a[i]) {
                lo = Math.min(lo, a[i]);
                hi = Math.max(hi, a[i]);
            }
        }
        return lo > hi ? new long[0]
                : new long[]{Float.floatToRawIntBits(lo) & 0xFFFF_FFFFL, Float.floatToRawIntBits(hi) & 0xFFFF_FFFFL};
    }

    private static long[] minMax(double[] a) {
        DoubleVector min = DoubleVector.broadcast(DOUBLES, Double.POSITIVE_INFINITY);
        DoubleVector max = DoubleVector.broadcast(DOUBLES, Double.NEGATIVE_INFINITY);
        int i = 0;
        for (; i + DOUBLES.length() <= a.length; i += DOUBLES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLES, a, i);
            VectorMask<Double> ordered = v.test(VectorOperators.IS_NAN).not();
            min = min.lanewise(VectorOperators.MIN, v, ordered);
            max = max.lanewise(VectorOperators.MAX, v, ordered);
        }
        double lo = min.reduceLanes(VectorOperators.MIN);
        double hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            if (a[i] == a[i]) {
                lo = Math.min(lo, a[i]);
                hi = Math.max(hi, a[i]);
            }
        }
        return lo > hi ? new long[0] : new long[]{Double.doubleToRawLongBits(lo), Double.doubleToRawLongBits(hi)};
    }

    // The Vector API has no half-float lanes, so this is a plain loop, the same as the reference.
    private static long[] minMaxHalf(short[] a) {
        float lo = 0f;
        float hi = 0f;
        short loBits = 0;
        short hiBits = 0;
        boolean seen = false;
        for (short v : a) {
            float f = Float.float16ToFloat(v);
            if (f != f) {
                continue;
            }
            if (!seen) {
                lo = f;
                hi = f;
                loBits = v;
                hiBits = v;
                seen = true;
                continue;
            }
            if (Float.compare(f, lo) < 0) {
                lo = f;
                loBits = v;
            }
            if (Float.compare(f, hi) > 0) {
                hi = f;
                hiBits = v;
            }
        }
        return seen ? new long[]{Short.toUnsignedLong(loBits), Short.toUnsignedLong(hiBits)} : new long[0];
    }

    // ---- allEqual ----

    /// Vectors compared per early-exit check of [#allEqual(Object, PType)]. Each block ORs the XOR against
    /// the first element and tests the accumulator once, so the loop body has no branch; a non-constant
    /// array is still rejected after one block.
    private static final int EQUAL_BLOCK_VECTORS = 16;

    // The differences from the first element are ORed over a block and tested once per block; floats by
    // their raw bits.
    @Override
    public boolean allEqual(Object values, PType ptype) {
        if (Array.getLength(values) == 0) {
            return true;
        }
        return switch (ptype) {
            case I8, U8 -> allEqual((byte[]) values);
            case I16, U16, F16 -> allEqual((short[]) values);
            case I32, U32 -> allEqual((int[]) values);
            case I64, U64 -> allEqual((long[]) values);
            case F32 -> allEqual((float[]) values);
            case F64 -> allEqual((double[]) values);
        };
    }

    @Override
    public boolean allEqual(boolean[] values) {
        if (values.length == 0) {
            return true;
        }
        ByteVector first = ByteVector.broadcast(BYTES, (byte) (values[0] ? 1 : 0));
        int step = BYTES.length();
        int i = 0;
        while (i + step * EQUAL_BLOCK_VECTORS <= values.length) {
            ByteVector diff = ByteVector.zero(BYTES);
            for (int end = i + step * EQUAL_BLOCK_VECTORS; i < end; i += step) {
                diff = diff.or(ByteVector.fromBooleanArray(BYTES, values, i).lanewise(VectorOperators.XOR, first));
            }
            if (diff.compare(VectorOperators.NE, (byte) 0).anyTrue()) {
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
        int step = BYTES.length();
        int i = 0;
        while (i + step * EQUAL_BLOCK_VECTORS <= a.length) {
            ByteVector diff = ByteVector.zero(BYTES);
            for (int end = i + step * EQUAL_BLOCK_VECTORS; i < end; i += step) {
                diff = diff.or(ByteVector.fromArray(BYTES, a, i).lanewise(VectorOperators.XOR, first));
            }
            if (diff.compare(VectorOperators.NE, (byte) 0).anyTrue()) {
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
        int step = SHORTS.length();
        int i = 0;
        while (i + step * EQUAL_BLOCK_VECTORS <= a.length) {
            ShortVector diff = ShortVector.zero(SHORTS);
            for (int end = i + step * EQUAL_BLOCK_VECTORS; i < end; i += step) {
                diff = diff.or(ShortVector.fromArray(SHORTS, a, i).lanewise(VectorOperators.XOR, first));
            }
            if (diff.compare(VectorOperators.NE, (short) 0).anyTrue()) {
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

    private static boolean allEqual(int[] a) {
        IntVector first = IntVector.broadcast(INTS, a[0]);
        int step = INTS.length();
        int i = 0;
        while (i + step * EQUAL_BLOCK_VECTORS <= a.length) {
            IntVector diff = IntVector.zero(INTS);
            for (int end = i + step * EQUAL_BLOCK_VECTORS; i < end; i += step) {
                diff = diff.or(IntVector.fromArray(INTS, a, i).lanewise(VectorOperators.XOR, first));
            }
            if (diff.compare(VectorOperators.NE, 0).anyTrue()) {
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

    private static boolean allEqual(long[] a) {
        LongVector first = LongVector.broadcast(LONGS, a[0]);
        int step = LONGS.length();
        int i = 0;
        while (i + step * EQUAL_BLOCK_VECTORS <= a.length) {
            LongVector diff = LongVector.zero(LONGS);
            for (int end = i + step * EQUAL_BLOCK_VECTORS; i < end; i += step) {
                diff = diff.or(LongVector.fromArray(LONGS, a, i).lanewise(VectorOperators.XOR, first));
            }
            if (diff.compare(VectorOperators.NE, 0L).anyTrue()) {
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

    private static boolean allEqual(float[] a) {
        int firstBits = Float.floatToRawIntBits(a[0]);
        IntVector first = IntVector.broadcast(INTS, firstBits);
        int step = FLOATS.length();
        int i = 0;
        while (i + step * EQUAL_BLOCK_VECTORS <= a.length) {
            IntVector diff = IntVector.zero(INTS);
            for (int end = i + step * EQUAL_BLOCK_VECTORS; i < end; i += step) {
                diff = diff.or(FloatVector.fromArray(FLOATS, a, i).reinterpretAsInts().lanewise(VectorOperators.XOR, first));
            }
            if (diff.compare(VectorOperators.NE, 0).anyTrue()) {
                return false;
            }
        }
        for (; i < a.length; i++) {
            if (Float.floatToRawIntBits(a[i]) != firstBits) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqual(double[] a) {
        long firstBits = Double.doubleToRawLongBits(a[0]);
        LongVector first = LongVector.broadcast(LONGS, firstBits);
        int step = DOUBLES.length();
        int i = 0;
        while (i + step * EQUAL_BLOCK_VECTORS <= a.length) {
            LongVector diff = LongVector.zero(LONGS);
            for (int end = i + step * EQUAL_BLOCK_VECTORS; i < end; i += step) {
                diff = diff.or(DoubleVector.fromArray(DOUBLES, a, i).reinterpretAsLongs().lanewise(VectorOperators.XOR, first));
            }
            if (diff.compare(VectorOperators.NE, 0L).anyTrue()) {
                return false;
            }
        }
        for (; i < a.length; i++) {
            if (Double.doubleToRawLongBits(a[i]) != firstBits) {
                return false;
            }
        }
        return true;
    }

    // ---- runs ----

    // Each vector is compared against the same elements shifted by one, so the changes are counted
    // without a dependency between iterations. NE on floating-point lanes is `!=`: NaN always differs.
    @Override
    public long runs(Object values, PType ptype) {
        if (Array.getLength(values) == 0) {
            return 0;
        }
        return 1 + switch (ptype) {
            case I8, U8 -> changes((byte[]) values);
            case I16, U16, F16 -> changes((short[]) values);
            case I32, U32 -> changes((int[]) values);
            case I64, U64 -> changes((long[]) values);
            case F32 -> changes((float[]) values);
            case F64 -> changes((double[]) values);
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

    // The comparison masks are accumulated as -1 lanes and reduced once, instead of counting each mask
    // with trueCount(), a cross-lane operation per vector. A lane gains at most one per vector, so the
    // 32-bit counters hold any array that fits in memory.
    private static long changes(int[] a) {
        IntVector count = IntVector.zero(INTS);
        int i = 1;
        for (; i + INTS.length() <= a.length; i += INTS.length()) {
            count = count.sub(IntVector.fromArray(INTS, a, i)
                    .compare(VectorOperators.NE, IntVector.fromArray(INTS, a, i - 1)).toVector());
        }
        long changes = laneSum(count);
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    private static long changes(long[] a) {
        LongVector count = LongVector.zero(LONGS);
        int i = 1;
        for (; i + LONGS.length() <= a.length; i += LONGS.length()) {
            count = count.sub(LongVector.fromArray(LONGS, a, i)
                    .compare(VectorOperators.NE, LongVector.fromArray(LONGS, a, i - 1)).toVector());
        }
        long changes = count.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    private static long changes(float[] a) {
        IntVector count = IntVector.zero(INTS);
        int i = 1;
        for (; i + FLOATS.length() <= a.length; i += FLOATS.length()) {
            count = count.sub(FloatVector.fromArray(FLOATS, a, i)
                    .compare(VectorOperators.NE, FloatVector.fromArray(FLOATS, a, i - 1)).cast(INTS).toVector());
        }
        long changes = laneSum(count);
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    private static long changes(double[] a) {
        LongVector count = LongVector.zero(LONGS);
        int i = 1;
        for (; i + DOUBLES.length() <= a.length; i += DOUBLES.length()) {
            count = count.sub(DoubleVector.fromArray(DOUBLES, a, i)
                    .compare(VectorOperators.NE, DoubleVector.fromArray(DOUBLES, a, i - 1)).cast(LONGS).toVector());
        }
        long changes = count.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    // ---- sum ----

    @Override
    public OptionalLong sum(Object values, PType ptype) {
        return switch (ptype) {
            case I8 -> OptionalLong.of(sum((byte[]) values, VectorOperators.B2I));
            case U8 -> OptionalLong.of(sum((byte[]) values, VectorOperators.ZERO_EXTEND_B2I));
            case I16 -> OptionalLong.of(sum((short[]) values, VectorOperators.S2I));
            case U16 -> OptionalLong.of(sum((short[]) values, VectorOperators.ZERO_EXTEND_S2I));
            case I32 -> OptionalLong.of(sum((int[]) values, VectorOperators.I2L));
            case U32 -> OptionalLong.of(sum((int[]) values, VectorOperators.ZERO_EXTEND_I2L));
            case I64 -> sumSigned((long[]) values);
            case U64 -> sumUnsigned((long[]) values);
            case F16, F32, F64 -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
        };
    }

    /// Sums bytes into `int` lanes, flushed to a `long` every [#SUM_FLUSH_ITERATIONS] vectors so a
    /// lane cannot overflow: it gains `parts` elements per vector, each below 2^8.
    private static long sum(byte[] a, VectorOperators.Conversion<Byte, Integer> widen) {
        int parts = BYTES.length() / INTS.length();
        long total = 0;
        IntVector acc = IntVector.zero(INTS);
        int iterations = 0;
        int i = 0;
        for (; i + BYTES.length() <= a.length; i += BYTES.length()) {
            ByteVector v = ByteVector.fromArray(BYTES, a, i);
            for (int part = 0; part < parts; part++) {
                acc = acc.add((IntVector) v.convertShape(widen, INTS, part));
            }
            if (++iterations == SUM_FLUSH_ITERATIONS) {
                total += laneSum(acc);
                acc = IntVector.zero(INTS);
                iterations = 0;
            }
        }
        total += laneSum(acc);
        boolean unsigned = widen == VectorOperators.ZERO_EXTEND_B2I;
        for (; i < a.length; i++) {
            total += unsigned ? Byte.toUnsignedLong(a[i]) : a[i];
        }
        return total;
    }

    private static long sum(short[] a, VectorOperators.Conversion<Short, Integer> widen) {
        int parts = SHORTS.length() / INTS.length();
        long total = 0;
        IntVector acc = IntVector.zero(INTS);
        int iterations = 0;
        int i = 0;
        for (; i + SHORTS.length() <= a.length; i += SHORTS.length()) {
            ShortVector v = ShortVector.fromArray(SHORTS, a, i);
            for (int part = 0; part < parts; part++) {
                acc = acc.add((IntVector) v.convertShape(widen, INTS, part));
            }
            if (++iterations == SUM_FLUSH_ITERATIONS) {
                total += laneSum(acc);
                acc = IntVector.zero(INTS);
                iterations = 0;
            }
        }
        total += laneSum(acc);
        boolean unsigned = widen == VectorOperators.ZERO_EXTEND_S2I;
        for (; i < a.length; i++) {
            total += unsigned ? Short.toUnsignedLong(a[i]) : a[i];
        }
        return total;
    }

    private static long sum(int[] a, VectorOperators.Conversion<Integer, Long> widen) {
        int parts = INTS.length() / LONGS.length();
        LongVector acc = LongVector.zero(LONGS);
        int i = 0;
        for (; i + INTS.length() <= a.length; i += INTS.length()) {
            IntVector v = IntVector.fromArray(INTS, a, i);
            for (int part = 0; part < parts; part++) {
                acc = acc.add((LongVector) v.convertShape(widen, LONGS, part));
            }
        }
        long total = acc.reduceLanes(VectorOperators.ADD);
        boolean unsigned = widen == VectorOperators.ZERO_EXTEND_I2L;
        for (; i < a.length; i++) {
            total += unsigned ? Integer.toUnsignedLong(a[i]) : a[i];
        }
        return total;
    }

    /// Adds the lanes of an `int` accumulator as `long`s: reducing in `int` would wrap even when every
    /// lane is in range (`reduceLanesToLong` casts after the `int` reduction, so it would wrap too).
    private static long laneSum(IntVector acc) {
        long total = 0;
        int parts = INTS.length() / LONGS.length();
        for (int part = 0; part < parts; part++) {
            total += ((LongVector) acc.convertShape(VectorOperators.I2L, LONGS, part)).reduceLanes(VectorOperators.ADD);
        }
        return total;
    }

    // Overflow is detected on each lane's running sum (the sign bit of (acc ^ sum) & (v ^ sum) is set
    // when the addition overflowed), not on the sequential prefix Rust checks, so the two can disagree
    // in both directions on arrays whose partial sums approach the limits. The lanes and the scalar
    // tail are then combined with exact checked adds.
    private static OptionalLong sumSigned(long[] a) {
        LongVector acc = LongVector.zero(LONGS);
        LongVector overflow = LongVector.zero(LONGS);
        int i = 0;
        for (; i + LONGS.length() <= a.length; i += LONGS.length()) {
            LongVector v = LongVector.fromArray(LONGS, a, i);
            LongVector next = acc.add(v);
            overflow = overflow.or(acc.lanewise(VectorOperators.XOR, next).and(v.lanewise(VectorOperators.XOR, next)));
            acc = next;
        }
        if (overflow.compare(VectorOperators.LT, 0L).anyTrue()) {
            return OptionalLong.empty();
        }
        long total = 0;
        try {
            for (long lane : acc.toArray()) {
                total = Math.addExact(total, lane);
            }
            for (; i < a.length; i++) {
                total = Math.addExact(total, a[i]);
            }
        } catch (ArithmeticException _) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total);
    }

    private static OptionalLong sumUnsigned(long[] a) {
        LongVector acc = LongVector.zero(LONGS);
        LongVector flip = LongVector.broadcast(LONGS, Long.MIN_VALUE);
        VectorMask<Long> carry = LongVector.zero(LONGS).compare(VectorOperators.LT, 0L);
        int i = 0;
        for (; i + LONGS.length() <= a.length; i += LONGS.length()) {
            LongVector next = acc.add(LongVector.fromArray(LONGS, a, i));
            // An unsigned add wrapped when the sum is below an addend; XOR with the sign bit orders unsigned as signed
            carry = carry.or(next.lanewise(VectorOperators.XOR, flip).compare(VectorOperators.LT, acc.lanewise(VectorOperators.XOR, flip)));
            acc = next;
        }
        if (carry.anyTrue()) {
            return OptionalLong.empty();
        }
        long total = 0;
        for (long lane : acc.toArray()) {
            long next = total + lane;
            if (Long.compareUnsigned(next, total) < 0) {
                return OptionalLong.empty();
            }
            total = next;
        }
        for (; i < a.length; i++) {
            long next = total + a[i];
            if (Long.compareUnsigned(next, total) < 0) {
                return OptionalLong.empty();
            }
            total = next;
        }
        return OptionalLong.of(total);
    }

    // Lane-wise accumulation, reduced once at the end. The order of additions therefore differs from
    // Rust's sequential f64 sum (and the JDK says reduceLanes(ADD) on floats may use "an arbitrary order
    // of operations, which may even vary over time"), so the last bits of the result can differ from
    // Rust's and from the auto-vectorized implementation's. F16 has no vector lanes: a plain loop.
    @Override
    public double sumFloating(Object values, PType ptype) {
        return switch (ptype) {
            case F32 -> sum((float[]) values);
            case F64 -> sum((double[]) values);
            case F16 -> {
                double s = 0;
                for (short v : (short[]) values) {
                    s += Float.float16ToFloat(v);
                }
                yield s;
            }
            default -> throw new IllegalArgumentException("not a floating-point ptype: " + ptype);
        };
    }

    private static double sum(float[] a) {
        int parts = FLOATS.length() / DOUBLES.length();
        DoubleVector acc = DoubleVector.zero(DOUBLES);
        int i = 0;
        for (; i + FLOATS.length() <= a.length; i += FLOATS.length()) {
            FloatVector v = FloatVector.fromArray(FLOATS, a, i);
            for (int part = 0; part < parts; part++) {
                acc = acc.add((DoubleVector) v.convertShape(VectorOperators.F2D, DOUBLES, part));
            }
        }
        double total = acc.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++) {
            total += a[i];
        }
        return total;
    }

    private static double sum(double[] a) {
        DoubleVector acc = DoubleVector.zero(DOUBLES);
        int i = 0;
        for (; i + DOUBLES.length() <= a.length; i += DOUBLES.length()) {
            acc = acc.add(DoubleVector.fromArray(DOUBLES, a, i));
        }
        double total = acc.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++) {
            total += a[i];
        }
        return total;
    }

    // ---- FastLanes ----

    // Rows outer, lanes inner: each row is a vector add of the previous row. The lane count is a power
    // of two of at least 16, so it is always a whole number of vectors.
    @Override
    public void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        LongVector maskVector = LongVector.broadcast(LONGS, mask);
        int rowBase = FastLanes.iterateIndex(0, 0);
        for (int lane = 0; lane < lanes; lane += LONGS.length()) {
            LongVector.fromArray(LONGS, deltas, rowBase + lane).add(LongVector.fromArray(LONGS, bases, lane))
                    .and(maskVector).intoArray(out, rowBase + lane);
        }
        for (int row = 1; row < typeBits; row++) {
            int base = FastLanes.iterateIndex(row, 0);
            for (int lane = 0; lane < lanes; lane += LONGS.length()) {
                LongVector.fromArray(LONGS, deltas, base + lane).add(LongVector.fromArray(LONGS, out, rowBase + lane))
                        .and(maskVector).intoArray(out, base + lane);
            }
            rowBase = base;
        }
    }

    @Override
    public void deltaChunk(long[] values, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        LongVector maskVector = LongVector.broadcast(LONGS, mask);
        int rowBase = FastLanes.iterateIndex(0, 0);
        for (int lane = 0; lane < lanes; lane += LONGS.length()) {
            LongVector.fromArray(LONGS, values, rowBase + lane).sub(LongVector.fromArray(LONGS, bases, lane))
                    .and(maskVector).intoArray(out, rowBase + lane);
        }
        for (int row = 1; row < typeBits; row++) {
            int base = FastLanes.iterateIndex(row, 0);
            for (int lane = 0; lane < lanes; lane += LONGS.length()) {
                LongVector.fromArray(LONGS, values, base + lane).sub(LongVector.fromArray(LONGS, values, rowBase + lane))
                        .and(maskVector).intoArray(out, base + lane);
            }
            rowBase = base;
        }
    }

    // Same structure as the auto-vectorized kernel: whether a row completes a word depends on the row
    // alone, so the flush branch is hoisted out of the lane loop, and each lane loop is vector ops over
    // contiguous lanes with the per-lane accumulators in a scratch array.
    @Override
    public void packBlock(long[] values, int offset, int bitWidth, int typeBits, long[] words) {
        int lanes = FastLanes.CHUNK / typeBits;
        long typeMask = FastLanes.lowMask(typeBits);
        long widthMask = bitWidth >= 64 ? -1L : (1L << bitWidth) - 1L;
        LongVector widthMaskVector = LongVector.broadcast(LONGS, widthMask);
        LongVector typeMaskVector = LongVector.broadcast(LONGS, typeMask);
        long[] acc = new long[lanes];
        int shift = 0;
        int word = 0;
        for (int row = 0; row < typeBits; row++) {
            int base = offset + FastLanes.iterateIndex(row, 0);
            int filled = shift + bitWidth;
            if (filled < typeBits) {
                for (int lane = 0; lane < lanes; lane += LONGS.length()) {
                    LongVector.fromArray(LONGS, acc, lane)
                            .or(LongVector.fromArray(LONGS, values, base + lane).and(widthMaskVector)
                                    .lanewise(VectorOperators.LSHL, shift))
                            .intoArray(acc, lane);
                }
                shift = filled;
            } else {
                int wordBase = word * lanes;
                int carry = filled - typeBits;
                int carryShift = bitWidth - carry;
                for (int lane = 0; lane < lanes; lane += LONGS.length()) {
                    LongVector value = LongVector.fromArray(LONGS, values, base + lane).and(widthMaskVector);
                    LongVector.fromArray(LONGS, acc, lane).or(value.lanewise(VectorOperators.LSHL, shift))
                            .and(typeMaskVector).intoArray(words, wordBase + lane);
                    if (carry > 0) {
                        value.lanewise(VectorOperators.LSHR, carryShift).intoArray(acc, lane);
                    } else {
                        LongVector.zero(LONGS).intoArray(acc, lane);
                    }
                }
                word++;
                shift = carry;
            }
        }
    }
}
