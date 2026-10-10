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
///
/// Only [SimdOperationsSupport] instantiates it, and only when `jdk.incubator.vector` is on the
/// module graph (`--add-modules jdk.incubator.vector`); without it this class fails to link and the
/// [AutoVectorizedSimdOperations] stays in effect. The contract is identical results for every
/// input, enforced by a differential test against that implementation.
///
/// Two kernels are plain loops by contract, not by choice: [#sumFloating] adds strictly left to
/// right, and the `I64`/`U64` [#sum] checks overflow per addition. Lane-wise accumulation would
/// reassociate both and change their results.
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

    @Override
    public void widenInto(MemorySegment src, long fromElement, int count, PType ptype, long[] out) {
        switch (ptype) {
            case I8, U8 -> {
                VectorOperators.Conversion<Byte, Long> widen = ptype == PType.U8
                        ? VectorOperators.ZERO_EXTEND_B2L : VectorOperators.B2L;
                int parts = BYTES.length() / LONGS.length();
                int i = 0;
                for (; i + BYTES.length() <= count; i += BYTES.length()) {
                    ByteVector v = ByteVector.fromMemorySegment(BYTES, src, fromElement + i, ByteOrder.LITTLE_ENDIAN);
                    for (int part = 0; part < parts; part++) {
                        ((LongVector) v.convertShape(widen, LONGS, part)).intoArray(out, i + part * LONGS.length());
                    }
                }
                for (; i < count; i++) {
                    long v = src.get(ValueLayout.JAVA_BYTE, fromElement + i);
                    out[i] = ptype == PType.U8 ? v & 0xFFL : v;
                }
            }
            case I16, U16, F16 -> {
                VectorOperators.Conversion<Short, Long> widen = ptype == PType.I16
                        ? VectorOperators.S2L : VectorOperators.ZERO_EXTEND_S2L;
                int parts = SHORTS.length() / LONGS.length();
                int i = 0;
                for (; i + SHORTS.length() <= count; i += SHORTS.length()) {
                    ShortVector v = ShortVector.fromMemorySegment(SHORTS, src, (fromElement + i) * 2, ByteOrder.LITTLE_ENDIAN);
                    for (int part = 0; part < parts; part++) {
                        ((LongVector) v.convertShape(widen, LONGS, part)).intoArray(out, i + part * LONGS.length());
                    }
                }
                for (; i < count; i++) {
                    long v = src.getAtIndex(VortexFormat.LE_SHORT, fromElement + i);
                    out[i] = ptype == PType.I16 ? v : v & 0xFFFFL;
                }
            }
            case I32, U32, F32 -> {
                VectorOperators.Conversion<Integer, Long> widen = ptype == PType.I32
                        ? VectorOperators.I2L : VectorOperators.ZERO_EXTEND_I2L;
                int parts = INTS.length() / LONGS.length();
                int i = 0;
                for (; i + INTS.length() <= count; i += INTS.length()) {
                    IntVector v = IntVector.fromMemorySegment(INTS, src, (fromElement + i) * 4, ByteOrder.LITTLE_ENDIAN);
                    for (int part = 0; part < parts; part++) {
                        ((LongVector) v.convertShape(widen, LONGS, part)).intoArray(out, i + part * LONGS.length());
                    }
                }
                for (; i < count; i++) {
                    long v = src.getAtIndex(VortexFormat.LE_INT, fromElement + i);
                    out[i] = ptype == PType.I32 ? v : v & 0xFFFF_FFFFL;
                }
            }
            case I64, U64, F64 -> {
                int i = 0;
                for (; i + LONGS.length() <= count; i += LONGS.length()) {
                    LongVector.fromMemorySegment(LONGS, src, (fromElement + i) * 8, ByteOrder.LITTLE_ENDIAN)
                            .intoArray(out, i);
                }
                for (; i < count; i++) {
                    out[i] = src.getAtIndex(VortexFormat.LE_LONG, fromElement + i);
                }
            }
        }
    }

    @Override
    public void widenArrayInto(Object values, int from, int count, PType ptype, long[] out) {
        switch (ptype) {
            case I8, U8 -> {
                byte[] a = (byte[]) values;
                VectorOperators.Conversion<Byte, Long> widen = ptype == PType.U8
                        ? VectorOperators.ZERO_EXTEND_B2L : VectorOperators.B2L;
                int parts = BYTES.length() / LONGS.length();
                int i = 0;
                for (; i + BYTES.length() <= count; i += BYTES.length()) {
                    ByteVector v = ByteVector.fromArray(BYTES, a, from + i);
                    for (int part = 0; part < parts; part++) {
                        ((LongVector) v.convertShape(widen, LONGS, part)).intoArray(out, i + part * LONGS.length());
                    }
                }
                for (; i < count; i++) {
                    out[i] = ptype == PType.U8 ? a[from + i] & 0xFFL : a[from + i];
                }
            }
            case I16, U16, F16 -> {
                short[] a = (short[]) values;
                VectorOperators.Conversion<Short, Long> widen = ptype == PType.I16
                        ? VectorOperators.S2L : VectorOperators.ZERO_EXTEND_S2L;
                int parts = SHORTS.length() / LONGS.length();
                int i = 0;
                for (; i + SHORTS.length() <= count; i += SHORTS.length()) {
                    ShortVector v = ShortVector.fromArray(SHORTS, a, from + i);
                    for (int part = 0; part < parts; part++) {
                        ((LongVector) v.convertShape(widen, LONGS, part)).intoArray(out, i + part * LONGS.length());
                    }
                }
                for (; i < count; i++) {
                    out[i] = ptype == PType.I16 ? a[from + i] : a[from + i] & 0xFFFFL;
                }
            }
            case I32, U32 -> {
                int[] a = (int[]) values;
                VectorOperators.Conversion<Integer, Long> widen = ptype == PType.U32
                        ? VectorOperators.ZERO_EXTEND_I2L : VectorOperators.I2L;
                int parts = INTS.length() / LONGS.length();
                int i = 0;
                for (; i + INTS.length() <= count; i += INTS.length()) {
                    IntVector v = IntVector.fromArray(INTS, a, from + i);
                    for (int part = 0; part < parts; part++) {
                        ((LongVector) v.convertShape(widen, LONGS, part)).intoArray(out, i + part * LONGS.length());
                    }
                }
                for (; i < count; i++) {
                    out[i] = ptype == PType.U32 ? a[from + i] & 0xFFFF_FFFFL : a[from + i];
                }
            }
            case I64, U64 -> {
                long[] a = (long[]) values;
                int i = 0;
                for (; i + LONGS.length() <= count; i += LONGS.length()) {
                    LongVector.fromArray(LONGS, a, from + i).intoArray(out, i);
                }
                for (; i < count; i++) {
                    out[i] = a[from + i];
                }
            }
            case F32 -> {
                float[] a = (float[]) values;
                int parts = INTS.length() / LONGS.length();
                int i = 0;
                for (; i + FLOATS.length() <= count; i += FLOATS.length()) {
                    IntVector v = FloatVector.fromArray(FLOATS, a, from + i).reinterpretAsInts();
                    for (int part = 0; part < parts; part++) {
                        ((LongVector) v.convertShape(VectorOperators.ZERO_EXTEND_I2L, LONGS, part))
                                .intoArray(out, i + part * LONGS.length());
                    }
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
            case I64, U64, F64 -> {
                int i = 0;
                for (; i + LONGS.length() <= n; i += LONGS.length()) {
                    LongVector.fromArray(LONGS, values, i).intoMemorySegment(dst, i * 8L, ByteOrder.LITTLE_ENDIAN);
                }
                for (; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_LONG, i, values[i]);
                }
            }
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
            case I16, U16 -> {
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
            case F16 -> {
                short[] a = (short[]) out;
                int i = 0;
                for (; i + SHORTS.length() <= n; i += SHORTS.length()) {
                    narrowToShorts(values, i).intoArray(a, i);
                }
                for (; i < n; i++) {
                    a[i] = (short) values[i];
                }
            }
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

    /// One byte vector from `BYTES.length()` longs: each long vector contracts into its own slice of
    /// the result (part `-k`), the other lanes zero, so the slices are combined with an OR.
    private static ByteVector narrowToBytes(long[] values, int from) {
        ByteVector result = ByteVector.zero(BYTES);
        int parts = BYTES.length() / LONGS.length();
        for (int part = 0; part < parts; part++) {
            result = result.or((ByteVector) LongVector.fromArray(LONGS, values, from + part * LONGS.length())
                    .convertShape(VectorOperators.L2B, BYTES, -part));
        }
        return result;
    }

    private static ShortVector narrowToShorts(long[] values, int from) {
        ShortVector result = ShortVector.zero(SHORTS);
        int parts = SHORTS.length() / LONGS.length();
        for (int part = 0; part < parts; part++) {
            result = result.or((ShortVector) LongVector.fromArray(LONGS, values, from + part * LONGS.length())
                    .convertShape(VectorOperators.L2S, SHORTS, -part));
        }
        return result;
    }

    private static IntVector narrowToInts(long[] values, int from) {
        IntVector result = IntVector.zero(INTS);
        int parts = INTS.length() / LONGS.length();
        for (int part = 0; part < parts; part++) {
            result = result.or((IntVector) LongVector.fromArray(LONGS, values, from + part * LONGS.length())
                    .convertShape(VectorOperators.L2I, INTS, -part));
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

    // Compare-and-blend instead of min/max: a comparison with NaN is false, which skips NaN, whereas
    // the lane-wise min/max would propagate it. The lane merge cannot keep "the first zero" the scalar
    // order gives (Math.min prefers -0.0), so a zero result is looked up in the array.
    private static long[] minMax(float[] a) {
        FloatVector min = FloatVector.broadcast(FLOATS, Float.POSITIVE_INFINITY);
        FloatVector max = FloatVector.broadcast(FLOATS, Float.NEGATIVE_INFINITY);
        int i = 0;
        for (; i + FLOATS.length() <= a.length; i += FLOATS.length()) {
            FloatVector v = FloatVector.fromArray(FLOATS, a, i);
            VectorMask<Float> lower = v.compare(VectorOperators.LT, min);
            VectorMask<Float> higher = v.compare(VectorOperators.GT, max);
            min = min.blend(v, lower);
            max = max.blend(v, higher);
        }
        float lo = min.reduceLanes(VectorOperators.MIN);
        float hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            if (a[i] < lo) {
                lo = a[i];
            }
            if (a[i] > hi) {
                hi = a[i];
            }
        }
        if (lo > hi) {
            return new long[0];
        }
        return new long[]{Float.floatToRawIntBits(lo == 0f ? firstZero(a) : lo) & 0xFFFF_FFFFL,
                Float.floatToRawIntBits(hi == 0f ? firstZero(a) : hi) & 0xFFFF_FFFFL};
    }

    private static float firstZero(float[] a) {
        for (float v : a) {
            if (v == 0f) {
                return v;
            }
        }
        throw new IllegalStateException("a zero extreme has a zero element");
    }

    private static long[] minMax(double[] a) {
        DoubleVector min = DoubleVector.broadcast(DOUBLES, Double.POSITIVE_INFINITY);
        DoubleVector max = DoubleVector.broadcast(DOUBLES, Double.NEGATIVE_INFINITY);
        int i = 0;
        for (; i + DOUBLES.length() <= a.length; i += DOUBLES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLES, a, i);
            VectorMask<Double> lower = v.compare(VectorOperators.LT, min);
            VectorMask<Double> higher = v.compare(VectorOperators.GT, max);
            min = min.blend(v, lower);
            max = max.blend(v, higher);
        }
        double lo = min.reduceLanes(VectorOperators.MIN);
        double hi = max.reduceLanes(VectorOperators.MAX);
        for (; i < a.length; i++) {
            if (a[i] < lo) {
                lo = a[i];
            }
            if (a[i] > hi) {
                hi = a[i];
            }
        }
        if (lo > hi) {
            return new long[0];
        }
        return new long[]{Double.doubleToRawLongBits(lo == 0d ? firstZero(a) : lo),
                Double.doubleToRawLongBits(hi == 0d ? firstZero(a) : hi)};
    }

    private static double firstZero(double[] a) {
        for (double v : a) {
            if (v == 0d) {
                return v;
            }
        }
        throw new IllegalStateException("a zero extreme has a zero element");
    }

    // The Vector API has no half-float lanes, so this is a plain loop.
    private static long[] minMaxHalf(short[] a) {
        float lo = Float.POSITIVE_INFINITY;
        float hi = Float.NEGATIVE_INFINITY;
        short loBits = 0;
        short hiBits = 0;
        for (short v : a) {
            float f = Float.float16ToFloat(v);
            if (f < lo) {
                lo = f;
                loBits = v;
            }
            if (f > hi) {
                hi = f;
                hiBits = v;
            }
        }
        return lo > hi ? new long[0] : new long[]{Short.toUnsignedLong(loBits), Short.toUnsignedLong(hiBits)};
    }

    // ---- allEqual ----

    // Each vector is compared with the first element broadcast; floats by their raw bits.
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

    private static boolean allEqual(int[] a) {
        IntVector first = IntVector.broadcast(INTS, a[0]);
        int i = 0;
        for (; i + INTS.length() <= a.length; i += INTS.length()) {
            if (IntVector.fromArray(INTS, a, i).compare(VectorOperators.NE, first).anyTrue()) {
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
        int i = 0;
        for (; i + LONGS.length() <= a.length; i += LONGS.length()) {
            if (LongVector.fromArray(LONGS, a, i).compare(VectorOperators.NE, first).anyTrue()) {
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
        int i = 0;
        for (; i + FLOATS.length() <= a.length; i += FLOATS.length()) {
            if (FloatVector.fromArray(FLOATS, a, i).reinterpretAsInts().compare(VectorOperators.NE, first).anyTrue()) {
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
        int i = 0;
        for (; i + DOUBLES.length() <= a.length; i += DOUBLES.length()) {
            if (DoubleVector.fromArray(DOUBLES, a, i).reinterpretAsLongs().compare(VectorOperators.NE, first).anyTrue()) {
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

    private static long changes(int[] a) {
        long changes = 0;
        int i = 1;
        for (; i + INTS.length() <= a.length; i += INTS.length()) {
            changes += IntVector.fromArray(INTS, a, i)
                    .compare(VectorOperators.NE, IntVector.fromArray(INTS, a, i - 1)).trueCount();
        }
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    private static long changes(long[] a) {
        long changes = 0;
        int i = 1;
        for (; i + LONGS.length() <= a.length; i += LONGS.length()) {
            changes += LongVector.fromArray(LONGS, a, i)
                    .compare(VectorOperators.NE, LongVector.fromArray(LONGS, a, i - 1)).trueCount();
        }
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    private static long changes(float[] a) {
        long changes = 0;
        int i = 1;
        for (; i + FLOATS.length() <= a.length; i += FLOATS.length()) {
            changes += FloatVector.fromArray(FLOATS, a, i)
                    .compare(VectorOperators.NE, FloatVector.fromArray(FLOATS, a, i - 1)).trueCount();
        }
        for (; i < a.length; i++) {
            changes += a[i] != a[i - 1] ? 1 : 0;
        }
        return changes;
    }

    private static long changes(double[] a) {
        long changes = 0;
        int i = 1;
        for (; i + DOUBLES.length() <= a.length; i += DOUBLES.length()) {
            changes += DoubleVector.fromArray(DOUBLES, a, i)
                    .compare(VectorOperators.NE, DoubleVector.fromArray(DOUBLES, a, i - 1)).trueCount();
        }
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

    /// Adds the lanes as `long`s: the `int` lane sum itself could wrap even when every lane is in range.
    private static long laneSum(IntVector acc) {
        long total = 0;
        for (int lane : acc.toArray()) {
            total += lane;
        }
        return total;
    }

    // A plain loop by contract: Rust drops the sum when any partial sum overflows, so the additions
    // must be checked in order.
    private static OptionalLong sumSigned(long[] a) {
        long total = 0;
        for (long v : a) {
            try {
                total = Math.addExact(total, v);
            } catch (ArithmeticException _) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.of(total);
    }

    private static OptionalLong sumUnsigned(long[] a) {
        long total = 0;
        for (long v : a) {
            long next = total + v;
            if (Long.compareUnsigned(next, total) < 0) {
                return OptionalLong.empty();
            }
            total = next;
        }
        return OptionalLong.of(total);
    }

    // A plain loop by contract: reassociating a float sum changes its rounding.
    @Override
    public double sumFloating(Object values, PType ptype) {
        double s = 0;
        switch (ptype) {
            case F16 -> {
                for (short v : (short[]) values) {
                    s += Float.float16ToFloat(v);
                }
            }
            case F32 -> {
                for (float v : (float[]) values) {
                    s += v;
                }
            }
            case F64 -> {
                for (double v : (double[]) values) {
                    s += v;
                }
            }
            default -> throw new IllegalArgumentException("not a floating-point ptype: " + ptype);
        }
        return s;
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
