package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;

/// The auto-vectorized [SimdOperations]: plain Java loops that rely on C2 auto-vectorization, with one
/// specialized loop per width and the `ptype` switch hoisted out (CLAUDE.md hot-loop rule), so each
/// body is uniform.
final class AutoVectorizedSimdOperations implements SimdOperations {

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
            case U16 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Short.toUnsignedLong(src.getAtIndex(VortexFormat.LE_SHORT, fromElement + i));
                }
            }
            case I32 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = src.getAtIndex(VortexFormat.LE_INT, fromElement + i);
                }
            }
            case U32 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Integer.toUnsignedLong(src.getAtIndex(VortexFormat.LE_INT, fromElement + i));
                }
            }
            case I64, U64 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = src.getAtIndex(VortexFormat.LE_LONG, fromElement + i);
                }
            }
            default -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
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
            case U16 -> {
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
            default -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
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
            default -> throw new IllegalArgumentException("not a 1-4 byte ptype: " + ptype);
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
            default -> throw new IllegalArgumentException("not a U8/U16/U32 ptype: " + ptype);
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
            case I64, U64 -> {
                long[] a = (long[]) values;
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (long v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            default -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
        };
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
