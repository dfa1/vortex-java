package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// Conversions between a boxed Java primitive value array and its wide / off-heap forms,
/// shared by the integer encodings on both the read and write sides.
///
/// [#toLongs(Object, PType, EncodingId)] and [#fromLongs(long[], PType, SegmentAllocator)] are
/// inverses: the first widens any 8–64 bit integer array to a `long[]`, the second writes a
/// `long[]` back to a little-endian off-heap segment of the target width. Floating-point ptypes
/// are not handled here — they reinterpret to raw bits or take type-specific encode paths instead.
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
        return switch (ptype) {
            case I8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = arr[i];
                }
                yield r;
            }
            case U8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Byte.toUnsignedLong(arr[i]);
                }
                yield r;
            }
            case I16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = arr[i];
                }
                yield r;
            }
            case U16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Short.toUnsignedLong(arr[i]);
                }
                yield r;
            }
            case I32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = arr[i];
                }
                yield r;
            }
            case U32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Integer.toUnsignedLong(arr[i]);
                }
                yield r;
            }
            case I64, U64 -> (long[]) data;
            default -> throw new VortexException(encoding, "unsupported ptype: " + ptype);
        };
    }

    /// Writes a `long[]` to a freshly allocated little-endian off-heap segment whose element width
    /// is that of `ptype`, narrowing each element to the low bytes. Inverse of
    /// [#toLongs(Object, PType, EncodingId)]. The I64/U64 case bulk-copies; narrower widths write
    /// element by element through [PTypeIO#set(MemorySegment, long, PType, long)].
    ///
    /// @param longs the wide values to write
    /// @param ptype the target primitive width
    /// @param arena allocator for the output segment
    /// @return a little-endian segment of `longs.length` elements at `ptype`'s width
    public static MemorySegment fromLongs(long[] longs, PType ptype, SegmentAllocator arena) {
        if (ptype == PType.I64 || ptype == PType.U64) {
            MemorySegment dst = arena.allocate((long) longs.length * 8);
            MemorySegment.copy(MemorySegment.ofArray(longs), ValueLayout.JAVA_LONG, 0L, dst, PTypeIO.LE_LONG, 0L, longs.length);
            return dst;
        }
        int n = longs.length;
        long elemSize = ptype.byteSize();
        MemorySegment seg = arena.allocate(n * elemSize);
        for (int i = 0; i < n; i++) {
            PTypeIO.set(seg, i * elemSize, ptype, longs[i]);
        }
        return seg;
    }
}
