package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Multi-chunk [Float16Array] record. ADR 0012 shape. Chunks are copied as raw 16-bit halves, so a
/// NaN payload survives [#materialize(SegmentAllocator)].
///
/// @param dtype    logical element type
/// @param length   total logical row count
/// @param children chunk arrays
/// @param offsets  cumulative row counts
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ChunkedFloat16Array(DType dtype, long length, Float16Array[] children, long[] offsets) implements Float16Array {

    /// Builds a [ChunkedFloat16Array].
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new [ChunkedFloat16Array]
    /// @throws VortexException on empty input, non-[Float16Array] chunks, or row-count mismatch
    public static ChunkedFloat16Array of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedFloat16Array: empty chunk list");
        }
        var typed = new ArrayList<Float16Array>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedFloat16Array: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedFloat16Array(dtype, totalRows, typed.toArray(Float16Array[]::new), off);
    }

    private static void flatten(Array chunk, List<Float16Array> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedFloat16Array nested) {
            Collections.addAll(out, nested.children);
        } else if (data instanceof Float16Array fa) {
            out.add(fa);
        } else {
            throw new VortexException("ChunkedFloat16Array: chunk is not a Float16Array: "
                    + data.getClass().getSimpleName());
        }
    }

    @Override
    public float getFloat(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getFloat(i - offsets[c]);
    }

    @Override
    public Array limited(long rows) {
        return ChunkedFloat16Array.of(dtype, rows, ChunkedArrays.limitedChildren(children, offsets, rows));
    }

    /// Materializes by concatenating each child's segment into one contiguous
    /// little-endian `f16` buffer, each child materialized through its own
    /// [Float16Array#materialize(SegmentAllocator)].
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only little-endian `f16` segment spanning all chunks
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        MemorySegment dst = arena.allocate(length * 2L, 2);
        long byteOffset = 0;
        for (Float16Array child : children) {
            MemorySegment src = child.materialize(arena);
            long bytes = child.length() * 2L;
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }
}
