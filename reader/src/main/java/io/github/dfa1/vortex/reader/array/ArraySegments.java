package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Utility for extracting the primary {@link MemorySegment} from any {@link Array}.
///
/// If `arr` is a {@link MaskedArray}, the inner (data) segment is returned;
/// the validity mask is not surfaced here — callers that need validity must unwrap manually.
///
/// **Internal utility.** This class is `public` only because the
/// vortex-java reader, writer, and encoding implementations live in separate Maven modules and
/// need cross-package access to the raw backing segment of typed arrays. It is not part of the
/// supported public API: signatures may change without a deprecation cycle. Application code
/// should prefer the typed accessors on concrete subtypes — {@link LongArray#getLong(long)},
/// {@link IntArray#getInt(long)}, {@link DoubleArray#getDouble(long)}, and friends — and treat
/// `ArraySegments` as a Vortex-internal escape hatch.
/// @deprecated this class should be removed gradually
@Deprecated
public final class ArraySegments {

    private ArraySegments() {
    }

    /// Returns the primary backing segment of `arr`.
    ///
    /// @param arr the array whose segment is needed
    /// @return the primary {@link MemorySegment}
    /// @throws VortexException if the array type has no primary segment (e.g. lazy variants — use
    ///                         {@link #of(Array, SegmentAllocator)} instead)
    public static MemorySegment of(Array arr) {
        Array data = arr instanceof MaskedArray m ? m.inner() : arr;
        return switch (data) {
            case MaterializedIntArray a -> a.buffer();
            case MaterializedLongArray a -> a.buffer();
            case MaterializedDoubleArray a -> a.buffer();
            case MaterializedFloatArray a -> a.buffer();
            case MaterializedShortArray a -> a.buffer();
            case MaterializedByteArray a -> a.buffer();
            case MaterializedBoolArray a -> a.buffer();
            case MaterializedFloat16Array a -> a.buffer();
            case VarBinArray a -> a.bytesSegment();
            case GenericArray a -> a.buffer(0);
            case LazyDecimalArray a -> a.buf();
            default -> throw new VortexException(data.getClass().getSimpleName() + " has no primary segment");
        };
    }

    /// Returns the primary backing segment of `arr`, materialising lazy variants into a
    /// fresh segment allocated from `arena`.
    ///
    /// Use this overload when the caller already holds a chunk-scoped allocator (e.g.
    /// {@link io.github.dfa1.vortex.reader.ReadRegistry#decodeAsSegment}) so lazy array types
    /// do not need to carry the arena as a record component.
    ///
    /// @param arr   the array whose segment is needed
    /// @param arena allocator used to materialise lazy variants
    /// @return the primary {@link MemorySegment}
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
            default -> of(arr);
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
}
