package io.github.dfa1.vortex.core.compute;

import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.simd.SimdOperations;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;

/// Conversions between a boxed Java primitive value array and its wide / off-heap forms,
/// shared by the integer encodings on both the read and write sides.
///
/// [#toLongs(Object, PType, EncodingId)] and [#fromLongs(long[], PType, SegmentAllocator)] are
/// inverses: the first widens any 8–64 bit integer array to a `long[]`, the second writes a
/// `long[]` back to a little-endian off-heap segment of the target width. Both are integer-only —
/// floating-point ptypes reinterpret to raw bits or take type-specific encode paths instead.
/// [#compact(PType, Object, boolean[])] covers every primitive ptype, integer and floating alike.
public final class PrimitiveArrays {

    private PrimitiveArrays() {
    }

    /// Widens a boxed primitive integer array to `long[]`, zero-extending the unsigned ptypes and
    /// sign-extending the signed ones. The I64/U64 case returns the input array directly (no copy).
    ///
    /// @param data     the value array; its runtime type must match `ptype`
    ///                 (`byte[]` for I8/U8, `short[]` for I16/U16, `int[]` for I32/U32, `long[]` for I64/U64)
    /// @param ptype    the logical primitive type of `data`
    /// @param encoding the encoding requesting the widening, used for error attribution
    /// @return a `long[]` holding every element of `data` widened to 64 bits
    /// @throws VortexException if `ptype` is not an integer ptype
    public static long[] toLongs(Object data, PType ptype, EncodingId encoding) {
        if (ptype.isFloating()) {
            throw new VortexException(encoding, "unsupported ptype: " + ptype);
        }
        if (ptype == PType.I64 || ptype == PType.U64) {
            return (long[]) data;
        }
        long[] result = new long[Array.getLength(data)];
        SimdOperationsSupport.preferred().widenArrayInto(data, 0, result.length, ptype, result);
        return result;
    }

    /// Throws unless `ptype` is one of the four unsigned integer types. Several wire fields (run
    /// ends, patch indices, chunk offsets) are untrusted `ptype` values read straight off proto
    /// metadata and are contractually unsigned; this is the single place that enforces it, so a
    /// crafted file naming a signed ptype there fails loudly instead of being silently
    /// misinterpreted as two's-complement.
    ///
    /// @param ptype    the ptype to check
    /// @param encoding the encoding requesting the check, used for error attribution
    /// @throws VortexException if `ptype` is not U8/U16/U32/U64
    public static void requireUnsigned(PType ptype, EncodingId encoding) {
        if (ptype != PType.U8 && ptype != PType.U16 && ptype != PType.U32 && ptype != PType.U64) {
            throw new VortexException(encoding, "expected an unsigned ptype, got " + ptype);
        }
    }

    /// Reads one integer element at a raw byte offset in `seg`, widened to `long` (zero-extending
    /// unsigned ptypes, sign-extending signed ones).
    ///
    /// @param seg        the source segment
    /// @param byteOffset byte offset of the element within `seg`
    /// @param ptype      the element's physical type
    /// @param encoding   the encoding requesting the read, used for error attribution
    /// @return the element widened to 64 bits
    /// @throws VortexException if `ptype` is not an integer ptype
    public static long readLong(MemorySegment seg, long byteOffset, PType ptype, EncodingId encoding) {
        return switch (ptype) {
            case I8 -> seg.get(ValueLayout.JAVA_BYTE, byteOffset);
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, byteOffset));
            case I16 -> seg.get(VortexFormat.LE_SHORT, byteOffset);
            case U16 -> Short.toUnsignedLong(seg.get(VortexFormat.LE_SHORT, byteOffset));
            case I32 -> seg.get(VortexFormat.LE_INT, byteOffset);
            case U32 -> Integer.toUnsignedLong(seg.get(VortexFormat.LE_INT, byteOffset));
            case I64, U64 -> seg.get(VortexFormat.LE_LONG, byteOffset);
            default -> throw new VortexException(encoding, "unsupported ptype: " + ptype);
        };
    }

    /// Widens `count` contiguous integer elements starting at element index `fromElement` in `seg`
    /// to `long[]`. Allocating sibling of [#toLongsInto(MemorySegment, long, int, PType,
    /// EncodingId, long[])] for call sites that don't already hold reusable scratch.
    ///
    /// @param seg         the source segment
    /// @param fromElement starting element index (not byte offset) within `seg`
    /// @param count       number of elements to read
    /// @param ptype       the elements' physical type
    /// @param encoding    the encoding requesting the read, used for error attribution
    /// @return a new `long[]` of length `count`
    /// @throws VortexException if `ptype` is not an integer ptype
    public static long[] toLongs(MemorySegment seg, long fromElement, int count, PType ptype, EncodingId encoding) {
        long[] out = new long[count];
        toLongsInto(seg, fromElement, count, ptype, encoding, out);
        return out;
    }

    /// Widens `count` contiguous integer elements starting at element index `fromElement` in `seg`
    /// into caller-supplied `out`, so a per-chunk hot loop can reuse one scratch array across calls
    /// instead of allocating on every chunk. The `ptype` switch is hoisted out of the loop — one
    /// specialized body per width rather than a per-element switch (CLAUDE.md hot-loop rule).
    ///
    /// @param seg         the source segment
    /// @param fromElement starting element index (not byte offset) within `seg`
    /// @param count       number of elements to read
    /// @param ptype       the elements' physical type
    /// @param encoding    the encoding requesting the read, used for error attribution
    /// @param out         destination array, filled at indices `[0, count)`
    /// @throws VortexException if `ptype` is not an integer ptype
    public static void toLongsInto(MemorySegment seg, long fromElement, int count, PType ptype,
            EncodingId encoding, long[] out) {
        if (ptype.isFloating()) {
            throw new VortexException(encoding, "unsupported ptype: " + ptype);
        }
        SimdOperationsSupport.preferred().widenInto(seg, fromElement, count, ptype, out);
    }

    /// Writes a `long[]` to a freshly allocated little-endian off-heap segment whose element width
    /// is that of `ptype`, narrowing each element to the low bytes. Inverse of
    /// [#toLongs(Object, PType, EncodingId)]. Eight-byte types bulk-copy; narrower widths run one
    /// specialized loop per width.
    ///
    /// @param longs the wide values to write
    /// @param ptype the target primitive width
    /// @param arena allocator for the output segment
    /// @return a little-endian segment of `longs.length` elements at `ptype`'s width
    public static MemorySegment fromLongs(long[] longs, PType ptype, SegmentAllocator arena) {
        if (ptype.byteSize() == 8) {
            MemorySegment dst = arena.allocate((long) longs.length * 8);
            MemorySegment.copy(MemorySegment.ofArray(longs), ValueLayout.JAVA_LONG, 0L, dst, VortexFormat.LE_LONG, 0L, longs.length);
            return dst;
        }
        int n = longs.length;
        MemorySegment seg = arena.allocate((long) n * ptype.byteSize());
        SimdOperationsSupport.preferred().narrowInto(longs, ptype, seg);
        return seg;
    }

    /// Copies a heap carrier array of any primitive type into a freshly allocated little-endian
    /// off-heap segment, aligned to `ptype`'s element width (the alignment an `EncodedBuffer`
    /// declares). One bulk copy per call; [MemorySegment#copy(Object, int, MemorySegment,
    /// ValueLayout, long, int)] does any byte swap.
    ///
    /// @param data  the heap carrier array (`byte[]` for I8/U8, `short[]` for I16/U16/F16,
    ///              `int[]` for I32/U32, `long[]` for I64/U64, `float[]` for F32, `double[]` for F64)
    /// @param ptype the primitive type of `data`
    /// @param arena allocator for the output segment
    /// @return a native little-endian segment holding every element of `data`
    public static MemorySegment toSegment(Object data, PType ptype, SegmentAllocator arena) {
        ValueLayout layout = switch (ptype) {
            case I8, U8 -> ValueLayout.JAVA_BYTE;
            case I16, U16, F16 -> VortexFormat.LE_SHORT;
            case I32, U32 -> VortexFormat.LE_INT;
            case I64, U64 -> VortexFormat.LE_LONG;
            case F32 -> VortexFormat.LE_FLOAT;
            case F64 -> VortexFormat.LE_DOUBLE;
        };
        int n = Array.getLength(data);
        MemorySegment seg = arena.allocate((long) n * ptype.byteSize(), ptype.byteSize());
        MemorySegment.copy(data, 0, seg, layout, 0, n);
        return seg;
    }

    /// Narrows `longs` back to `ptype`'s heap carrier array (`byte[]` for I8/U8, `short[]` for
    /// I16/U16, `int[]` for I32/U32, `long[]` for I64/U64) — the inverse of
    /// [#toLongs(Object, PType, EncodingId)]. Truncation keeps the low bits, which round-trips
    /// signed and unsigned values alike. Like `toLongs`, the I64/U64 case returns `longs` itself.
    ///
    /// @param longs    the wide values
    /// @param ptype    the target integer type
    /// @param encoding the encoding id used in the error message for unsupported types
    /// @return the carrier array, `longs.length` elements
    /// @throws VortexException for floating-point or other non-integer types
    public static Object fromLongsArray(long[] longs, PType ptype, EncodingId encoding) {
        int n = longs.length;
        SimdOperations ops = SimdOperationsSupport.preferred();
        return switch (ptype) {
            case I8, U8 -> {
                byte[] r = new byte[n];
                ops.narrowArrayInto(longs, ptype, r);
                yield r;
            }
            case I16, U16 -> {
                short[] r = new short[n];
                ops.narrowArrayInto(longs, ptype, r);
                yield r;
            }
            case I32, U32 -> {
                int[] r = new int[n];
                ops.narrowArrayInto(longs, ptype, r);
                yield r;
            }
            case I64, U64 -> longs;
            default -> throw new VortexException(encoding, "unsupported ptype: " + ptype);
        };
    }

    /// Converts raw bit patterns to `ptype`'s heap carrier array, for every primitive type: integers
    /// as [#fromLongsArray(long[], PType, EncodingId)] does, `F16` as its 16 bits in a `short[]`,
    /// `F32`/`F64` reinterpreted from their IEEE-754 bits into `float[]`/`double[]`. The inverse of
    /// reading each element's raw bits, so `-0.0` and NaN payloads round-trip exactly.
    ///
    /// @param bits     each element's raw bits, in the low bits of the `long`
    /// @param ptype    the target primitive type
    /// @param encoding the encoding id used in the error message for unsupported types
    /// @return the carrier array, `bits.length` elements
    /// @throws VortexException for a type with no primitive carrier
    public static Object fromBitsArray(long[] bits, PType ptype, EncodingId encoding) {
        int n = bits.length;
        SimdOperations ops = SimdOperationsSupport.preferred();
        return switch (ptype) {
            case F16 -> {
                short[] r = new short[n];
                ops.narrowArrayInto(bits, ptype, r);
                yield r;
            }
            case F32 -> {
                float[] r = new float[n];
                ops.narrowArrayInto(bits, ptype, r);
                yield r;
            }
            case F64 -> {
                double[] r = new double[n];
                ops.narrowArrayInto(bits, ptype, r);
                yield r;
            }
            default -> fromLongsArray(bits, ptype, encoding);
        };
    }

    /// Narrows (or widens) `ints` to `ptype`'s heap carrier array (`byte[]` for I8/U8, `short[]`
    /// for I16/U16, `int[]` for I32/U32, `long[]` for I64/U64) — the `int[]` counterpart of
    /// [#fromLongsArray(long[], PType, EncodingId)], for the codes, offsets and indices encoders
    /// compute as `int`s before choosing their narrowest type. Truncation keeps the low bits; the
    /// I32/U32 case returns `ints` itself.
    ///
    /// @param ints     the values, already within `ptype`'s range
    /// @param ptype    the target integer type
    /// @param encoding the encoding id used in the error message for unsupported types
    /// @return the carrier array, `ints.length` elements
    /// @throws VortexException for floating-point or other non-integer types
    public static Object fromIntsArray(int[] ints, PType ptype, EncodingId encoding) {
        int n = ints.length;
        return switch (ptype) {
            case I8, U8 -> {
                byte[] r = new byte[n];
                for (int i = 0; i < n; i++) {
                    r[i] = (byte) ints[i];
                }
                yield r;
            }
            case I16, U16 -> {
                short[] r = new short[n];
                for (int i = 0; i < n; i++) {
                    r[i] = (short) ints[i];
                }
                yield r;
            }
            case I32, U32 -> ints;
            case I64, U64 -> {
                long[] r = new long[n];
                for (int i = 0; i < n; i++) {
                    r[i] = ints[i];
                }
                yield r;
            }
            default -> throw new VortexException(encoding, "unsupported ptype: " + ptype);
        };
    }

    /// Copies only the elements at `true` positions in `mask` from `data`, preserving `ptype`'s
    /// storage array shape (`byte[]` for I8/U8, `short[]` for I16/U16/F16, `int[]` for I32/U32,
    /// `long[]` for I64/U64, `float[]` for F32, `double[]` for F64). Covers every primitive ptype,
    /// unlike [#toLongs(Object, PType, EncodingId)]/[#fromLongs(long[], PType, SegmentAllocator)],
    /// which are integer-only.
    ///
    /// Used to strip a nullable column's dense, placeholder-filled values down to its real
    /// (row-valid) values before an operation that must not see the placeholder — a `byte[]`/
    /// `int[]`/... has no way to represent "no value", so a nullable column's invalid slots carry
    /// some placeholder chosen by the caller, commonly `0`; running e.g. a min/max scan over the
    /// raw array would fold that placeholder in as if it were real data.
    ///
    /// @param ptype the logical primitive type of `data`
    /// @param data  the value array; its runtime type must match `ptype`
    /// @param mask  per-element validity, aligned with `data` (`true` = keep)
    /// @return a new array of the same runtime type as `data`, holding only the `true`-masked elements
    public static Object compact(PType ptype, Object data, boolean[] mask) {
        int n = 0;
        for (boolean v : mask) {
            if (v) {
                n++;
            }
        }
        return switch (ptype) {
            case I8, U8 -> {
                byte[] src = (byte[]) data;
                byte[] out = new byte[n];
                int j = 0;
                for (int i = 0; i < src.length; i++) {
                    if (mask[i]) {
                        out[j++] = src[i];
                    }
                }
                yield out;
            }
            case I16, U16, F16 -> {
                short[] src = (short[]) data;
                short[] out = new short[n];
                int j = 0;
                for (int i = 0; i < src.length; i++) {
                    if (mask[i]) {
                        out[j++] = src[i];
                    }
                }
                yield out;
            }
            case I32, U32 -> {
                int[] src = (int[]) data;
                int[] out = new int[n];
                int j = 0;
                for (int i = 0; i < src.length; i++) {
                    if (mask[i]) {
                        out[j++] = src[i];
                    }
                }
                yield out;
            }
            case I64, U64 -> {
                long[] src = (long[]) data;
                long[] out = new long[n];
                int j = 0;
                for (int i = 0; i < src.length; i++) {
                    if (mask[i]) {
                        out[j++] = src[i];
                    }
                }
                yield out;
            }
            case F32 -> {
                float[] src = (float[]) data;
                float[] out = new float[n];
                int j = 0;
                for (int i = 0; i < src.length; i++) {
                    if (mask[i]) {
                        out[j++] = src[i];
                    }
                }
                yield out;
            }
            case F64 -> {
                double[] src = (double[]) data;
                double[] out = new double[n];
                int j = 0;
                for (int i = 0; i < src.length; i++) {
                    if (mask[i]) {
                        out[j++] = src[i];
                    }
                }
                yield out;
            }
        };
    }
}
