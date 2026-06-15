package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.List;

/// Multi-chunk {@link BoolArray} record. ADR 0012 shape.
///
/// Bit-packed boolean columns store one bit per row across the children's
/// segments; this record dispatches each scalar read to the chunk that
/// owns the row and lets the chunk handle the bit unpacking.
///
/// @param dtype    logical element type (Bool)
/// @param length   total logical row count
/// @param children chunk arrays in scan order
/// @param offsets  cumulative row counts; length = {@code children.length + 1}
public record ChunkedBoolArray(DType dtype, long length, BoolArray[] children, long[] offsets) implements BoolArray {

    /// Builds a {@link ChunkedBoolArray}.
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new {@link ChunkedBoolArray}
    /// @throws VortexException on empty input, non-{@link BoolArray} chunks, or row-count mismatch
    public static ChunkedBoolArray of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedBoolArray: empty chunk list");
        }
        var typed = new ArrayList<BoolArray>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedBoolArray: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedBoolArray(dtype, totalRows, typed.toArray(BoolArray[]::new), off);
    }

    private static void flatten(Array chunk, List<BoolArray> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedBoolArray nested) {
            for (BoolArray child : nested.children) {
                out.add(child);
            }
        } else if (data instanceof BoolArray ba) {
            out.add(ba);
        } else {
            throw new VortexException("ChunkedBoolArray: chunk is not a BoolArray: "
                    + data.getClass().getSimpleName());
        }
    }

    @Override
    public boolean getBoolean(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getBoolean(i - offsets[c]);
    }
}
