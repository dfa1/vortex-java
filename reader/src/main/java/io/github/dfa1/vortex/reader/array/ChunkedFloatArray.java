package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

/// Multi-chunk {@link FloatArray} record. ADR 0012 shape.
///
/// @param dtype    logical element type
/// @param length   total logical row count
/// @param children chunk arrays
/// @param offsets  cumulative row counts
public record ChunkedFloatArray(DType dtype, long length, FloatArray[] children, long[] offsets) implements FloatArray {

    /// Builds a {@link ChunkedFloatArray}.
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new {@link ChunkedFloatArray}
    /// @throws VortexException on empty input, non-{@link FloatArray} chunks, or row-count mismatch
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
            for (FloatArray child : nested.children) {
                out.add(child);
            }
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
}
