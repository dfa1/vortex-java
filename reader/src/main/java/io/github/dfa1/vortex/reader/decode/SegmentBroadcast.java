package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;

/// Element-offset helper for child segments returned by DecodeContext#decodeChildSegment.
///
/// <p>ConstantEncodingEncoder deliberately stores a single element regardless of the array's
/// declared {@code rowCount} (zip-bomb defense). Consumers that bulk-read child segments by
/// raw byte offset (e.g. {@code seg.get(LE_LONG, i * 8)} or
/// {@code MemorySegment.copy(seg, i * elemBytes, ...)}) must replicate the lone element across
/// every logical index instead of running off the end.
///
/// <p>{@link io.github.dfa1.vortex.core.array.LongArray#getLong(long)} and the sibling typed
/// accessors already wrap around with {@code i % cap}; this helper exposes the same arithmetic
/// to call sites that work with raw segments.
public final class SegmentBroadcast {

    private SegmentBroadcast() {
    }

    /// Returns the byte offset of logical element {@code i} in {@code seg}, broadcasting if
    /// {@code seg} holds fewer elements than {@code i}.
    ///
    /// @param seg       segment returned by DecodeContext#decodeChildSegment
    /// @param i         logical element index in {@code [0, rowCount)}
    /// @param elemBytes element width in bytes (>= 1)
    /// @return byte offset suitable for {@link MemorySegment#get} or
    ///         {@link MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)}
    /// @throws VortexException if {@code seg} is empty (zero elements)
    public static long elementOffset(MemorySegment seg, long i, int elemBytes) {
        long cap = seg.byteSize() / elemBytes;
        if (cap == 0) {
            throw new VortexException("decoded child segment is empty (byteSize=" + seg.byteSize()
                    + ", elemBytes=" + elemBytes + ")");
        }
        return (i % cap) * elemBytes;
    }

    /// Returns the number of elements physically present in {@code seg} (≥ 1 for non-empty).
    ///
    /// <p>Used by callers that want to detect the constant case explicitly — e.g. to hoist
    /// a single read out of a hot loop.
    ///
    /// @param seg       segment returned by DecodeContext#decodeChildSegment
    /// @param elemBytes element width in bytes (>= 1)
    /// @return physical element count
    public static long capacity(MemorySegment seg, int elemBytes) {
        return seg.byteSize() / elemBytes;
    }

    /// Copies {@code count} elements from {@code src} to {@code dst}, broadcasting the lone
    /// element of {@code src} across {@code count} indices when {@code src} holds fewer
    /// elements than {@code count} (typically when {@code src} came from
    /// ConstantEncodingEncoder).
    ///
    /// @param src       source segment (must hold ≥ 1 element)
    /// @param dst       destination segment (must hold ≥ {@code count * elemBytes} bytes)
    /// @param count     number of elements to materialize
    /// @param elemBytes element width in bytes (>= 1)
    /// @throws VortexException if {@code src} is empty
    public static void broadcastCopy(MemorySegment src, MemorySegment dst, long count, int elemBytes) {
        long cap = src.byteSize() / elemBytes;
        if (cap == 0) {
            throw new VortexException("source segment is empty (byteSize=" + src.byteSize()
                    + ", elemBytes=" + elemBytes + ")");
        }
        if (cap >= count) {
            MemorySegment.copy(src, 0, dst, 0, count * elemBytes);
            return;
        }
        for (long i = 0; i < count; i++) {
            MemorySegment.copy(src, (i % cap) * elemBytes, dst, i * elemBytes, elemBytes);
        }
    }
}
