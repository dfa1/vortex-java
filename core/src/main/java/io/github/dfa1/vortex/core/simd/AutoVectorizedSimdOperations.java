package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;
import java.util.OptionalLong;

/// The auto-vectorized [SimdOperations]: plain Java loops that rely on C2 auto-vectorization, with one
/// specialized loop per width and the `ptype` switch hoisted out (CLAUDE.md hot-loop rule), so each
/// body is uniform.
final class AutoVectorizedSimdOperations implements SimdOperations {

    /// Elements compared per branch-free block of [#allEqual(Object, PType)].
    private static final int EQUAL_BLOCK = 256;

    @Override
    public void widenInto(MemorySegment src, long fromElement, int count, PType ptype, long[] out) {
        switch (ptype) {
            case I8 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = src.get(ValueLayout.JAVA_BYTE, fromElement + i);
                }
            }
            case U8 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Byte.toUnsignedLong(src.get(ValueLayout.JAVA_BYTE, fromElement + i));
                }
            }
            case I16 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = src.getAtIndex(VortexFormat.LE_SHORT, fromElement + i);
                }
            }
            case U16, F16 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Short.toUnsignedLong(src.getAtIndex(VortexFormat.LE_SHORT, fromElement + i));
                }
            }
            case I32 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = src.getAtIndex(VortexFormat.LE_INT, fromElement + i);
                }
            }
            case U32, F32 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Integer.toUnsignedLong(src.getAtIndex(VortexFormat.LE_INT, fromElement + i));
                }
            }
            case I64, U64, F64 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = src.getAtIndex(VortexFormat.LE_LONG, fromElement + i);
                }
            }
        }
    }

    @Override
    public void widenArrayInto(Object values, int from, int count, PType ptype, long[] out) {
        switch (ptype) {
            case I8 -> {
                byte[] a = (byte[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = a[from + i];
                }
            }
            case U8 -> {
                byte[] a = (byte[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = a[from + i] & 0xFFL;
                }
            }
            case I16 -> {
                short[] a = (short[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = a[from + i];
                }
            }
            case U16, F16 -> {
                short[] a = (short[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = a[from + i] & 0xFFFFL;
                }
            }
            case I32 -> {
                int[] a = (int[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = a[from + i];
                }
            }
            case U32 -> {
                int[] a = (int[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = a[from + i] & 0xFFFF_FFFFL;
                }
            }
            case I64, U64 -> System.arraycopy((long[]) values, from, out, 0, count);
            case F32 -> {
                float[] a = (float[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = Float.floatToRawIntBits(a[from + i]) & 0xFFFF_FFFFL;
                }
            }
            case F64 -> {
                double[] a = (double[]) values;
                for (int i = 0; i < count; i++) {
                    out[i] = Double.doubleToRawLongBits(a[from + i]);
                }
            }
        }
    }

    @Override
    public void narrowInto(long[] values, PType ptype, MemorySegment dst) {
        int n = values.length;
        switch (ptype) {
            case I8, U8 -> {
                for (int i = 0; i < n; i++) {
                    dst.set(ValueLayout.JAVA_BYTE, i, (byte) values[i]);
                }
            }
            case I16, U16, F16 -> {
                for (int i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_SHORT, i, (short) values[i]);
                }
            }
            case I32, U32, F32 -> {
                for (int i = 0; i < n; i++) {
                    dst.setAtIndex(VortexFormat.LE_INT, i, (int) values[i]);
                }
            }
            case I64, U64, F64 -> {
                for (int i = 0; i < n; i++) {
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
                for (int i = 0; i < n; i++) {
                    a[i] = (byte) values[i];
                }
            }
            case I16, U16 -> {
                short[] a = (short[]) out;
                for (int i = 0; i < n; i++) {
                    a[i] = (short) values[i];
                }
            }
            case I32, U32 -> {
                int[] a = (int[]) out;
                for (int i = 0; i < n; i++) {
                    a[i] = (int) values[i];
                }
            }
            case I64, U64 -> System.arraycopy(values, 0, (long[]) out, 0, n);
            case F16 -> {
                short[] a = (short[]) out;
                for (int i = 0; i < n; i++) {
                    a[i] = (short) values[i];
                }
            }
            case F32 -> {
                float[] a = (float[]) out;
                for (int i = 0; i < n; i++) {
                    a[i] = Float.intBitsToFloat((int) values[i]);
                }
            }
            case F64 -> {
                double[] a = (double[]) out;
                for (int i = 0; i < n; i++) {
                    a[i] = Double.longBitsToDouble(values[i]);
                }
            }
        }
    }

    // Reduced in the int domain: NEON has no 64-bit integer max, so a long accumulator keeps C2 from
    // vectorizing on aarch64. U32 flips the sign bit so a signed int max orders it unsigned, and C2
    // vectorizes it. C2 does not vectorize a U8/U16 reduction (widening to int), so those run four
    // independent accumulators instead: one serial max chain is latency-bound, four overlap (2.2x).
    @Override
    public long maxUnsigned(MemorySegment src, long count, PType ptype) {
        return switch (ptype) {
            case U8 -> maxU8(src, count);
            case U16 -> maxU16(src, count);
            case U32 -> maxU32(src, count);
            case U64 -> maxU64(src, count);
            default -> throw new IllegalArgumentException("not an unsigned integer ptype: " + ptype);
        };
    }

    private static long maxU8(MemorySegment src, long count) {
        int m0 = 0;
        int m1 = 0;
        int m2 = 0;
        int m3 = 0;
        long body = count & ~3L;
        for (long i = 0; i < body; i += 4) {
            m0 = Math.max(m0, Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i)));
            m1 = Math.max(m1, Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i + 1)));
            m2 = Math.max(m2, Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i + 2)));
            m3 = Math.max(m3, Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i + 3)));
        }
        for (long i = body; i < count; i++) {
            m0 = Math.max(m0, Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i)));
        }
        return Math.max(Math.max(m0, m1), Math.max(m2, m3));
    }

    private static long maxU16(MemorySegment src, long count) {
        int m0 = 0;
        int m1 = 0;
        int m2 = 0;
        int m3 = 0;
        long body = count & ~3L;
        for (long i = 0; i < body; i += 4) {
            m0 = Math.max(m0, Short.toUnsignedInt(src.getAtIndex(VortexFormat.LE_SHORT, i)));
            m1 = Math.max(m1, Short.toUnsignedInt(src.getAtIndex(VortexFormat.LE_SHORT, i + 1)));
            m2 = Math.max(m2, Short.toUnsignedInt(src.getAtIndex(VortexFormat.LE_SHORT, i + 2)));
            m3 = Math.max(m3, Short.toUnsignedInt(src.getAtIndex(VortexFormat.LE_SHORT, i + 3)));
        }
        for (long i = body; i < count; i++) {
            m0 = Math.max(m0, Short.toUnsignedInt(src.getAtIndex(VortexFormat.LE_SHORT, i)));
        }
        return Math.max(Math.max(m0, m1), Math.max(m2, m3));
    }

    // Flipping the sign bit orders the signed max as unsigned; NEON has no 64-bit max, so this is a
    // scalar compare-and-select loop whatever the flip.
    private static long maxU64(MemorySegment src, long count) {
        long max = Long.MIN_VALUE;
        for (long i = 0; i < count; i++) {
            max = Math.max(max, src.getAtIndex(VortexFormat.LE_LONG, i) ^ Long.MIN_VALUE);
        }
        return max ^ Long.MIN_VALUE;
    }

    private static long maxU32(MemorySegment src, long count) {
        int max = Integer.MIN_VALUE;
        for (long i = 0; i < count; i++) {
            max = Math.max(max, src.getAtIndex(VortexFormat.LE_INT, i) ^ Integer.MIN_VALUE);
        }
        return Integer.toUnsignedLong(max ^ Integer.MIN_VALUE);
    }

    // Reduced in the int domain: NEON has no 64-bit integer min/max, so C2 vectorizes these loops for
    // `int`, `short` and `byte` but not over a widened `long` buffer (#476). Unsigned widths are masked
    // in the loop, which keeps them in their carrier's lanes.
    @Override
    public long[] minMax(Object values, PType ptype) {
        if (Array.getLength(values) == 0) {
            if (ptype.isFloating()) {
                return new long[0];
            }
            throw new IllegalArgumentException("empty array has no min/max");
        }
        return switch (ptype) {
            case I8 -> {
                byte[] a = (byte[]) values;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (byte v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            case U8 -> {
                byte[] a = (byte[]) values;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (byte v : a) {
                    min = Math.min(min, v & 0xFF);
                    max = Math.max(max, v & 0xFF);
                }
                yield new long[]{min, max};
            }
            case I16 -> {
                short[] a = (short[]) values;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (short v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            case U16 -> {
                short[] a = (short[]) values;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (short v : a) {
                    min = Math.min(min, v & 0xFFFF);
                    max = Math.max(max, v & 0xFFFF);
                }
                yield new long[]{min, max};
            }
            case I32 -> {
                int[] a = (int[]) values;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (int v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            case U32 -> {
                // Flipping the sign bit maps unsigned order onto signed order, so the loop stays
                // a plain int min/max.
                int[] a = (int[]) values;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (int v : a) {
                    min = Math.min(min, v ^ Integer.MIN_VALUE);
                    max = Math.max(max, v ^ Integer.MIN_VALUE);
                }
                yield new long[]{Integer.toUnsignedLong(min ^ Integer.MIN_VALUE),
                        Integer.toUnsignedLong(max ^ Integer.MIN_VALUE)};
            }
            case I64 -> {
                long[] a = (long[]) values;
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (long v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            case U64 -> {
                // Flipping the sign bit maps unsigned order onto signed order
                long[] a = (long[]) values;
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (long v : a) {
                    min = Math.min(min, v ^ Long.MIN_VALUE);
                    max = Math.max(max, v ^ Long.MIN_VALUE);
                }
                yield new long[]{min ^ Long.MIN_VALUE, max ^ Long.MIN_VALUE};
            }
            // The floating-point loops compare with `<` and `>` starting from the infinities: every
            // comparison with NaN is false, which skips NaN without a per-element branch, and an equal
            // zero never replaces the first one. An all-NaN array ends with min > max.
            case F16 -> {
                short[] a = (short[]) values;
                float min = Float.POSITIVE_INFINITY;
                float max = Float.NEGATIVE_INFINITY;
                short minBits = 0;
                short maxBits = 0;
                for (short v : a) {
                    float f = Float.float16ToFloat(v);
                    if (f < min) {
                        min = f;
                        minBits = v;
                    }
                    if (f > max) {
                        max = f;
                        maxBits = v;
                    }
                }
                yield min > max ? new long[0] : new long[]{Short.toUnsignedLong(minBits), Short.toUnsignedLong(maxBits)};
            }
            case F32 -> {
                float[] a = (float[]) values;
                float min = Float.POSITIVE_INFINITY;
                float max = Float.NEGATIVE_INFINITY;
                for (float v : a) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield min > max ? new long[0] : new long[]{
                        Float.floatToRawIntBits(min) & 0xFFFF_FFFFL, Float.floatToRawIntBits(max) & 0xFFFF_FFFFL};
            }
            case F64 -> {
                double[] a = (double[]) values;
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (double v : a) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield min > max ? new long[0] : new long[]{Double.doubleToRawLongBits(min), Double.doubleToRawLongBits(max)};
            }
        };
    }

    // Block-wise: each block ORs the XOR against the first element, which is branch-free and so
    // vectorizes, and the early exit happens between blocks. A non-constant array, the common case,
    // is rejected after one block; a per-element exit would stop C2 from vectorizing the body.
    @Override
    public boolean allEqual(Object values, PType ptype) {
        if (Array.getLength(values) == 0) {
            return true;
        }
        return switch (ptype) {
            case I8, U8 -> allEqualBytes((byte[]) values);
            case I16, U16, F16 -> allEqualShorts((short[]) values);
            case I32, U32 -> allEqualInts((int[]) values);
            case I64, U64 -> allEqualLongs((long[]) values);
            case F32 -> allEqualFloatBits((float[]) values);
            case F64 -> allEqualDoubleBits((double[]) values);
        };
    }

    @Override
    public boolean allEqual(boolean[] values) {
        if (values.length == 0) {
            return true;
        }
        boolean first = values[0];
        for (int base = 0; base < values.length; base += EQUAL_BLOCK) {
            int end = Math.min(base + EQUAL_BLOCK, values.length);
            int diff = 0;
            for (int i = base; i < end; i++) {
                diff |= values[i] != first ? 1 : 0;
            }
            if (diff != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqualBytes(byte[] a) {
        byte first = a[0];
        for (int base = 0; base < a.length; base += EQUAL_BLOCK) {
            int end = Math.min(base + EQUAL_BLOCK, a.length);
            int diff = 0;
            for (int i = base; i < end; i++) {
                diff |= a[i] ^ first;
            }
            if (diff != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqualShorts(short[] a) {
        short first = a[0];
        for (int base = 0; base < a.length; base += EQUAL_BLOCK) {
            int end = Math.min(base + EQUAL_BLOCK, a.length);
            int diff = 0;
            for (int i = base; i < end; i++) {
                diff |= a[i] ^ first;
            }
            if (diff != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqualInts(int[] a) {
        int first = a[0];
        for (int base = 0; base < a.length; base += EQUAL_BLOCK) {
            int end = Math.min(base + EQUAL_BLOCK, a.length);
            int diff = 0;
            for (int i = base; i < end; i++) {
                diff |= a[i] ^ first;
            }
            if (diff != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqualLongs(long[] a) {
        long first = a[0];
        for (int base = 0; base < a.length; base += EQUAL_BLOCK) {
            int end = Math.min(base + EQUAL_BLOCK, a.length);
            long diff = 0;
            for (int i = base; i < end; i++) {
                diff |= a[i] ^ first;
            }
            if (diff != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqualFloatBits(float[] a) {
        int first = Float.floatToRawIntBits(a[0]);
        for (int base = 0; base < a.length; base += EQUAL_BLOCK) {
            int end = Math.min(base + EQUAL_BLOCK, a.length);
            int diff = 0;
            for (int i = base; i < end; i++) {
                diff |= Float.floatToRawIntBits(a[i]) ^ first;
            }
            if (diff != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqualDoubleBits(double[] a) {
        long first = Double.doubleToRawLongBits(a[0]);
        for (int base = 0; base < a.length; base += EQUAL_BLOCK) {
            int end = Math.min(base + EQUAL_BLOCK, a.length);
            long diff = 0;
            for (int i = base; i < end; i++) {
                diff |= Double.doubleToRawLongBits(a[i]) ^ first;
            }
            if (diff != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public OptionalLong sum(Object values, PType ptype) {
        return switch (ptype) {
            case I8 -> {
                long s = 0;
                for (byte v : (byte[]) values) {
                    s += v;
                }
                yield OptionalLong.of(s);
            }
            case I16 -> {
                long s = 0;
                for (short v : (short[]) values) {
                    s += v;
                }
                yield OptionalLong.of(s);
            }
            case I32 -> {
                long s = 0;
                for (int v : (int[]) values) {
                    s += v;
                }
                yield OptionalLong.of(s);
            }
            case I64 -> {
                long s = 0;
                for (long v : (long[]) values) {
                    try {
                        s = Math.addExact(s, v);
                    } catch (ArithmeticException _) {
                        yield OptionalLong.empty();
                    }
                }
                yield OptionalLong.of(s);
            }
            case U8 -> {
                long s = 0;
                for (byte v : (byte[]) values) {
                    s += Byte.toUnsignedLong(v);
                }
                yield OptionalLong.of(s);
            }
            case U16 -> {
                long s = 0;
                for (short v : (short[]) values) {
                    s += Short.toUnsignedLong(v);
                }
                yield OptionalLong.of(s);
            }
            case U32 -> {
                long s = 0;
                for (int v : (int[]) values) {
                    s += Integer.toUnsignedLong(v);
                }
                yield OptionalLong.of(s);
            }
            case U64 -> {
                long s = 0;
                for (long v : (long[]) values) {
                    long next = s + v;
                    if (Long.compareUnsigned(next, s) < 0) {
                        yield OptionalLong.empty();
                    }
                    s = next;
                }
                yield OptionalLong.of(s);
            }
            default -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
        };
    }

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

    // Branch-free body per carrier, so C2 vectorizes it (CLAUDE.md hot-loop rule).
    @Override
    public long runs(Object values, PType ptype) {
        int n = Array.getLength(values);
        if (n == 0) {
            return 0;
        }
        long changes = 0;
        switch (ptype) {
            case I8, U8 -> {
                byte[] a = (byte[]) values;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case I16, U16, F16 -> {
                short[] a = (short[]) values;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case I32, U32 -> {
                int[] a = (int[]) values;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case I64, U64 -> {
                long[] a = (long[]) values;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case F32 -> {
                float[] a = (float[]) values;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case F64 -> {
                double[] a = (double[]) values;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
        }
        return changes + 1;
    }

    // Only the result is masked: the low typeBits bits of a sum or difference depend on the operands'
    // low bits alone, so masking each operand too would be redundant work.
    // Rows outer, lanes inner: lanes are independent and contiguous (iterateIndex(row, lane) is
    // ITERATE_BASE[row] + lane), so the inner body is uniform and C2 can vectorize it. The row-to-row
    // dependency is carried through memory, reading the previous row's already-written output.
    @Override
    public void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        int rowBase = FastLanes.iterateIndex(0, 0);
        for (int lane = 0; lane < lanes; lane++) {
            out[rowBase + lane] = (deltas[rowBase + lane] + bases[lane]) & mask;
        }
        for (int row = 1; row < typeBits; row++) {
            int base = FastLanes.iterateIndex(row, 0);
            for (int lane = 0; lane < lanes; lane++) {
                out[base + lane] = (deltas[base + lane] + out[rowBase + lane]) & mask;
            }
            rowBase = base;
        }
    }

    @Override
    public void deltaChunk(long[] values, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        int rowBase = FastLanes.iterateIndex(0, 0);
        for (int lane = 0; lane < lanes; lane++) {
            out[rowBase + lane] = (values[rowBase + lane] - bases[lane]) & mask;
        }
        for (int row = 1; row < typeBits; row++) {
            int base = FastLanes.iterateIndex(row, 0);
            for (int lane = 0; lane < lanes; lane++) {
                out[base + lane] = (values[base + lane] - values[rowBase + lane]) & mask;
            }
            rowBase = base;
        }
    }

    // Rows outer, lanes inner. Whether a row completes a word depends on the row alone, never on the
    // lane, so the flush branch is hoisted out of the lane loop and each lane loop is a uniform,
    // contiguous body (iterateIndex(row, lane) is ITERATE_BASE[row] + lane) that C2 can vectorize.
    // Per-lane accumulators live in a small scratch array instead of registers.
    @Override
    public void packBlock(long[] values, int offset, int bitWidth, int typeBits, long[] words) {
        int lanes = FastLanes.CHUNK / typeBits;
        long typeMask = FastLanes.lowMask(typeBits);
        long widthMask = bitWidth >= 64 ? -1L : (1L << bitWidth) - 1L;
        long[] acc = new long[lanes];
        int shift = 0;
        int word = 0;
        for (int row = 0; row < typeBits; row++) {
            int base = offset + FastLanes.iterateIndex(row, 0);
            int filled = shift + bitWidth;
            if (filled < typeBits) {
                for (int lane = 0; lane < lanes; lane++) {
                    acc[lane] |= (values[base + lane] & widthMask) << shift;
                }
                shift = filled;
            } else {
                int wordBase = word * lanes;
                int carry = filled - typeBits;
                int carryShift = bitWidth - carry;
                if (carry > 0) {
                    for (int lane = 0; lane < lanes; lane++) {
                        long value = values[base + lane] & widthMask;
                        words[wordBase + lane] = (acc[lane] | (value << shift)) & typeMask;
                        acc[lane] = value >>> carryShift;
                    }
                } else {
                    for (int lane = 0; lane < lanes; lane++) {
                        long value = values[base + lane] & widthMask;
                        words[wordBase + lane] = (acc[lane] | (value << shift)) & typeMask;
                        acc[lane] = 0L;
                    }
                }
                word++;
                shift = carry;
            }
        }
    }
}
