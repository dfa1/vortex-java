package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

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

    /// Returns the primary backing segment of {@code arr}, materialising lazy variants through
    /// their chunk-scoped allocator on demand.
    ///
    /// @param arr the array whose segment is needed
    /// @return the primary {@link MemorySegment}
    /// @throws VortexException if the array type has no primary segment
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
            case LazyAlpDoubleArray a -> materialise(a);
            case LazyForLongArray a -> materialise(a);
            case LazyForIntArray a -> materialise(a);
            case LazyZigZagLongArray a -> materialise(a);
            case LazyZigZagIntArray a -> materialise(a);
            case VarBinArray a -> a.bytesSegment();
            case GenericArray a -> a.buffer(0);
            default -> throw new VortexException(data.getClass().getSimpleName() + " has no primary segment");
        };
    }

    private static MemorySegment materialise(LazyAlpDoubleArray a) {
        long n = a.length();
        MemorySegment dst = a.arena().allocate(n * 8L, 8);
        double scale = a.scale();
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) src.getAtIndex(PTypeIO.LE_LONG, i) * scale);
        }
        return dst;
    }

    private static MemorySegment materialise(LazyForLongArray a) {
        long n = a.length();
        MemorySegment dst = a.arena().allocate(n * 8L, 8);
        long ref = a.ref();
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_LONG, i, src.getAtIndex(PTypeIO.LE_LONG, i) + ref);
        }
        return dst;
    }

    private static MemorySegment materialise(LazyForIntArray a) {
        long n = a.length();
        MemorySegment dst = a.arena().allocate(n * 4L, 4);
        int ref = a.ref();
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_INT, i, src.getAtIndex(PTypeIO.LE_INT, i) + ref);
        }
        return dst;
    }

    private static MemorySegment materialise(LazyZigZagLongArray a) {
        long n = a.length();
        MemorySegment dst = a.arena().allocate(n * 8L, 8);
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            long u = src.getAtIndex(PTypeIO.LE_LONG, i);
            dst.setAtIndex(PTypeIO.LE_LONG, i, (u >>> 1) ^ -(u & 1L));
        }
        return dst;
    }

    private static MemorySegment materialise(LazyZigZagIntArray a) {
        long n = a.length();
        MemorySegment dst = a.arena().allocate(n * 4L, 4);
        MemorySegment src = a.encoded();
        for (long i = 0; i < n; i++) {
            int u = src.getAtIndex(PTypeIO.LE_INT, i);
            dst.setAtIndex(PTypeIO.LE_INT, i, (u >>> 1) ^ -(u & 1));
        }
        return dst;
    }
}
