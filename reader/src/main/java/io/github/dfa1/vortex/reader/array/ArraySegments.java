package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;

/// Internal materialization engine: turns any [Array] into its primary
/// [MemorySegment], allocating from a caller-supplied arena for lazy variants.
///
/// If `arr` is a [MaskedArray], the inner (data) segment is returned;
/// the validity mask is not surfaced here — callers that need validity must unwrap manually.
///
/// **Vortex-internal — not public API.** This class is `public` only because the reader,
/// writer, and encoding implementations live in separate Maven modules and need cross-package
/// access; its signatures may change without a deprecation cycle. Encoding decoders should not
/// call it directly — use [io.github.dfa1.vortex.reader.decode.DecodeContext#materialize(Array)]
/// (which routes here). It backs that seam plus
/// [io.github.dfa1.vortex.reader.ReadRegistry#decodeAsSegment] and the scan layer's dictionary
/// validation. Application code should prefer the typed accessors on concrete subtypes —
/// [LongArray#getLong(long)], [IntArray#getInt(long)],
/// [DoubleArray#getDouble(long)], and friends.
public final class ArraySegments {

    private ArraySegments() {
    }

    /// Returns the primary backing segment of `arr` if it is segment-backed, otherwise empty.
    ///
    /// Non-throwing probe for callers that want to operate on the raw buffer only when one
    /// exists (e.g. zone-map / capacity validation) and skip lazy variants without allocating.
    /// To force a segment for a lazy array, use [#of(Array, SegmentAllocator)].
    ///
    /// @param arr the array whose segment is needed
    /// @return the primary [MemorySegment], or empty if `arr` has no segment backing
    public static Optional<MemorySegment> trySegment(Array arr) {
        Array data = arr instanceof MaskedArray m ? m.inner() : arr;
        return switch (data) {
            case MaterializedIntArray a -> Optional.of(a.buffer());
            case MaterializedLongArray a -> Optional.of(a.buffer());
            case MaterializedDoubleArray a -> Optional.of(a.buffer());
            case MaterializedFloatArray a -> Optional.of(a.buffer());
            case MaterializedShortArray a -> Optional.of(a.buffer());
            case MaterializedByteArray a -> Optional.of(a.buffer());
            case MaterializedBoolArray a -> Optional.of(a.buffer());
            case MaterializedFloat16Array a -> Optional.of(a.buffer());
            case VarBinArray a -> Optional.of(a.bytesSegment());
            case GenericArray a -> Optional.of(a.buffer(0));
            case LazyDecimalArray a -> Optional.of(a.buf());
            default -> Optional.empty();
        };
    }

    private static MemorySegment primarySegment(Array arr) {
        return trySegment(arr).orElseThrow(() -> {
            Array data = arr instanceof MaskedArray m ? m.inner() : arr;
            return new VortexException(data.getClass().getSimpleName() + " has no primary segment — use of(arr, arena)");
        });
    }

    /// Returns the primary backing segment of `arr`, materialising lazy variants into a
    /// fresh segment allocated from `arena`.
    ///
    /// Use this overload when the caller already holds a chunk-scoped allocator (e.g.
    /// [io.github.dfa1.vortex.reader.ReadRegistry#decodeAsSegment]) so lazy array types
    /// do not need to carry the arena as a record component.
    ///
    /// @param arr   the array whose segment is needed
    /// @param arena allocator used to materialise lazy variants
    /// @return the primary [MemorySegment]
    /// @throws VortexException if the array type has no primary segment
    public static MemorySegment of(Array arr, SegmentAllocator arena) {
        Array data = arr instanceof MaskedArray m ? m.inner() : arr;
        return switch (data) {
            case LazyAlpDoubleArray a -> materialise(a, arena);
            case LazyAlpFloatArray a -> materialise(a, arena);
            case LazyForLongArray a -> materialise(a, arena);
            case LazyForIntArray a -> materialise(a, arena);
            case LazyZigZagLongArray a -> materialise(a, arena);
            case LazyZigZagIntArray a -> materialise(a, arena);
            case ChunkedLongArray a -> materialiseChunkedLong(a, arena);
            case ChunkedIntArray a -> materialiseChunkedInt(a, arena);
            case ChunkedDoubleArray a -> materialiseChunkedDouble(a, arena);
            case ChunkedFloatArray a -> materialiseChunkedFloat(a, arena);
            case ChunkedShortArray a -> materialiseChunkedShort(a, arena);
            case ChunkedByteArray a -> materialiseChunkedByte(a, arena);
            case DictLongArray a -> materialiseDictLong(a, arena);
            case DictIntArray a -> materialiseDictInt(a, arena);
            case DictDoubleArray a -> materialiseDictDouble(a, arena);
            case DictFloatArray a -> materialiseDictFloat(a, arena);
            // Generic typed-accessor fallback: any LongArray/IntArray/.../etc. that
            // is not segment-backed (e.g. LazyRle*, LazyRunEnd*, LazySparse*, LazyAlpRd*)
            // can be materialised via its public typed accessor without a special case.
            case LongArray a -> materialiseLong(a, arena);
            case IntArray a -> materialiseInt(a, arena);
            case DoubleArray a -> materialiseDouble(a, arena);
            case FloatArray a -> materialiseFloat(a, arena);
            case ShortArray a -> materialiseShort(a, arena);
            case ByteArray a -> materialiseByte(a, arena);
            case LazyConstantDecimalArray a -> materialiseConstantDecimal(a, arena);
            case DecimalArray _ -> primarySegment(arr);
            default -> primarySegment(arr);
        };
    }

    private static MemorySegment materialiseLong(LongArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_LONG, i, a.getLong(i));
        }
        return dst;
    }

    private static MemorySegment materialiseInt(IntArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_INT, i, a.getInt(i));
        }
        return dst;
    }

    private static MemorySegment materialiseDouble(DoubleArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_DOUBLE, i, a.getDouble(i));
        }
        return dst;
    }

    private static MemorySegment materialiseFloat(FloatArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_FLOAT, i, a.getFloat(i));
        }
        return dst;
    }

    private static MemorySegment materialiseShort(ShortArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 2L, 2);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_SHORT, i, a.getShort(i));
        }
        return dst;
    }

    private static MemorySegment materialiseByte(ByteArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n);
        for (long i = 0; i < n; i++) {
            dst.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, a.getByte(i));
        }
        return dst;
    }

    private static MemorySegment materialiseChunkedLong(ChunkedLongArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        long byteOffset = 0;
        for (LongArray child : a.children()) {
            MemorySegment src = of((Array) child, arena);
            long bytes = child.length() * 8L;
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseChunkedInt(ChunkedIntArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        long byteOffset = 0;
        for (IntArray child : a.children()) {
            MemorySegment src = of((Array) child, arena);
            long bytes = child.length() * 4L;
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseChunkedDouble(ChunkedDoubleArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        long byteOffset = 0;
        for (DoubleArray child : a.children()) {
            MemorySegment src = of((Array) child, arena);
            long bytes = child.length() * 8L;
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseChunkedFloat(ChunkedFloatArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        long byteOffset = 0;
        for (FloatArray child : a.children()) {
            MemorySegment src = of((Array) child, arena);
            long bytes = child.length() * 4L;
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialise(LazyAlpDoubleArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        double df = a.factorF();
        double de = a.factorE();
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) src.getAtIndex(PTypeIO.LE_LONG, i) * df * de);
        }
        return dst;
    }

    private static MemorySegment materialise(LazyAlpFloatArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        float df = a.factorF();
        float de = a.factorE();
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_FLOAT, i, (float) src.getAtIndex(PTypeIO.LE_INT, i) * df * de);
        }
        return dst;
    }

    private static MemorySegment materialise(LazyForLongArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        long ref = a.ref();
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_LONG, i, src.getAtIndex(PTypeIO.LE_LONG, i) + ref);
        }
        return dst;
    }

    private static MemorySegment materialise(LazyForIntArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        int ref = a.ref();
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_INT, i, src.getAtIndex(PTypeIO.LE_INT, i) + ref);
        }
        return dst;
    }

    private static MemorySegment materialise(LazyZigZagLongArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            long u = src.getAtIndex(PTypeIO.LE_LONG, i);
            dst.setAtIndex(PTypeIO.LE_LONG, i, (u >>> 1) ^ -(u & 1L));
        }
        return dst;
    }

    private static MemorySegment materialise(LazyZigZagIntArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            int u = src.getAtIndex(PTypeIO.LE_INT, i);
            dst.setAtIndex(PTypeIO.LE_INT, i, (u >>> 1) ^ -(u & 1));
        }
        return dst;
    }

    private static MemorySegment materialiseChunkedShort(ChunkedShortArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 2L, 2);
        long byteOffset = 0;
        for (ShortArray child : a.children()) {
            MemorySegment src = of((Array) child, arena);
            long bytes = child.length() * 2L;
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseChunkedByte(ChunkedByteArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n);
        long byteOffset = 0;
        for (ByteArray child : a.children()) {
            MemorySegment src = of((Array) child, arena);
            long bytes = child.length();
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseDictLong(DictLongArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        LongArray vals = a.values();
        Array codes = a.codes();
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_LONG, i, vals.getLong(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_LONG, i, vals.getLong(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_LONG, i, vals.getLong(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_LONG, i, vals.getLong(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictLongArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseDictInt(DictIntArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        IntArray vals = a.values();
        Array codes = a.codes();
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_INT, i, vals.getInt(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_INT, i, vals.getInt(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_INT, i, vals.getInt(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_INT, i, vals.getInt(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictIntArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseDictDouble(DictDoubleArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 8L, 8);
        DoubleArray vals = a.values();
        Array codes = a.codes();
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_DOUBLE, i, vals.getDouble(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictDoubleArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseDictFloat(DictFloatArray a, SegmentAllocator arena) {
        long n = a.length();
        MemorySegment dst = arena.allocate(n * 4L, 4);
        FloatArray vals = a.values();
        Array codes = a.codes();
        switch (codes) {
            case ByteArray ba -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_FLOAT, i, vals.getFloat(Byte.toUnsignedLong(ba.getByte(i))));
                }
            }
            case ShortArray sa -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_FLOAT, i, vals.getFloat(Short.toUnsignedLong(sa.getShort(i))));
                }
            }
            case IntArray ia -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_FLOAT, i, vals.getFloat(Integer.toUnsignedLong(ia.getInt(i))));
                }
            }
            case LongArray la -> {
                for (long i = 0; i < n; i++) {
                    dst.setAtIndex(PTypeIO.LE_FLOAT, i, vals.getFloat(la.getLong(i)));
                }
            }
            default -> throw new VortexException("DictFloatArray: invalid codes type: "
                    + codes.getClass().getSimpleName());
        }
        return dst.asReadOnly();
    }

    private static MemorySegment materialiseConstantDecimal(LazyConstantDecimalArray a, SegmentAllocator arena) {
        long n = a.length();
        int byteWidth = a.byteWidth();
        MemorySegment dst = arena.allocate(n * byteWidth);
        java.math.BigInteger unscaled = a.value().unscaledValue();
        // Write the single constant value in LE two's-complement, repeated n times.
        long rawBits = unscaled.longValueExact();
        for (long i = 0; i < n; i++) {
            long off = i * byteWidth;
            switch (byteWidth) {
                case 1 -> dst.set(java.lang.foreign.ValueLayout.JAVA_BYTE, off, (byte) rawBits);
                case 2 -> dst.set(PTypeIO.LE_SHORT, off, (short) rawBits);
                case 4 -> dst.set(PTypeIO.LE_INT, off, (int) rawBits);
                case 8 -> dst.set(PTypeIO.LE_LONG, off, rawBits);
                default -> throw new VortexException("LazyConstantDecimalArray: unsupported byteWidth " + byteWidth);
            }
        }
        return dst.asReadOnly();
    }
}
