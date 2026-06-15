package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Utility for extracting the primary {@link MemorySegment} from any {@link Array}.
///
/// <p>If {@code arr} is a {@link MaskedArray}, the inner (data) segment is returned;
/// the validity mask is not surfaced here — callers that need validity must unwrap manually.
///
/// <p><strong>Internal utility.</strong> This class is {@code public} only because the
/// vortex-java reader, writer, and encoding implementations live in separate Maven modules and
/// need cross-package access to the raw backing segment of typed arrays. It is not part of the
/// supported public API: signatures may change without a deprecation cycle. Application code
/// should prefer the typed accessors on concrete subtypes — {@link LongArray#getLong(long)},
/// {@link IntArray#getInt(long)}, {@link DoubleArray#getDouble(long)}, and friends — and treat
/// {@code ArraySegments} as a Vortex-internal escape hatch.
public final class ArraySegments {

    private ArraySegments() {
    }

    /// Returns the primary backing segment of {@code arr}.
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
            default -> throw new VortexException(data.getClass().getSimpleName() + " has no primary segment");
        };
    }

    /// Returns the primary backing segment of {@code arr}, materialising lazy variants into a
    /// fresh segment allocated from {@code arena}.
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
            default -> of(arr);
        };
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
}
