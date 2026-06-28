package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

/// Multi-chunk [FloatArray] record. ADR 0012 shape.
///
/// @param dtype    logical element type
/// @param length   total logical row count
/// @param children chunk arrays
/// @param offsets  cumulative row counts
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ChunkedFloatArray(DType dtype, long length, FloatArray[] children, long[] offsets) implements FloatArray {

    /// Builds a [ChunkedFloatArray].
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new [ChunkedFloatArray]
    /// @throws VortexException on empty input, non-[FloatArray] chunks, or row-count mismatch
    public static ChunkedFloatArray of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedFloatArray: empty chunk list");
        }
        var typed = new ArrayList<FloatArray>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedFloatArray: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedFloatArray(dtype, totalRows, typed.toArray(FloatArray[]::new), off);
    }

    private static void flatten(Array chunk, List<FloatArray> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedFloatArray nested) {
            Collections.addAll(out, nested.children);
        } else if (data instanceof FloatArray fa) {
            out.add(fa);
        } else {
            throw new VortexException("ChunkedFloatArray: chunk is not a FloatArray: "
                    + data.getClass().getSimpleName());
        }
    }

    @Override
    public float getFloat(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getFloat(i - offsets[c]);
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        double result = identity;
        for (FloatArray child : children) {
            result = child.fold(result, op);
        }
        return result;
    }

    @Override
    public Array limited(long rows) {
        return ChunkedFloatArray.of(dtype, rows, ChunkedArrays.limitedChildren(children, offsets, rows));
    }

    /// Materializes by concatenating each child's segment into one contiguous
    /// little-endian `f32` buffer, each child materialized through its own
    /// [FloatArray#materialize(SegmentAllocator)].
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only little-endian `f32` segment spanning all chunks
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        MemorySegment dst = arena.allocate(length * 4L, 4);
        long byteOffset = 0;
        for (FloatArray child : children) {
            MemorySegment src = child.materialize(arena);
            long bytes = child.length() * 4L;
            MemorySegment.copy(src, 0, dst, byteOffset, bytes);
            byteOffset += bytes;
        }
        return dst.asReadOnly();
    }
}
