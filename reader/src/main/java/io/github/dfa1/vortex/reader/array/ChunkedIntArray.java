package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Multi-chunk {@link IntArray} record. ADR 0012 shape.
///
/// @param dtype    logical element type
/// @param length   total logical row count
/// @param children chunk arrays
/// @param offsets  cumulative row counts
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ChunkedIntArray(DType dtype, long length, IntArray[] children, long[] offsets) implements IntArray {

    /// Builds a {@link ChunkedIntArray}.
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new {@link ChunkedIntArray}
    /// @throws VortexException on empty input, non-{@link IntArray} chunks, or row-count mismatch
    public static ChunkedIntArray of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedIntArray: empty chunk list");
        }
        var typed = new ArrayList<IntArray>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedIntArray: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedIntArray(dtype, totalRows, typed.toArray(IntArray[]::new), off);
    }

    private static void flatten(Array chunk, List<IntArray> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedIntArray nested) {
            for (IntArray child : nested.children) {
                out.add(child);
            }
        } else if (data instanceof IntArray ia) {
            out.add(ia);
        } else {
            throw new VortexException("ChunkedIntArray: chunk is not an IntArray: "
                    + data.getClass().getSimpleName());
        }
    }

    @Override
    public int getInt(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getInt(i - offsets[c]);
    }

    @Override
    public void forEachInt(IntConsumer cons) {
        for (IntArray child : children) {
            child.forEachInt(cons);
        }
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        int result = identity;
        for (IntArray child : children) {
            result = child.fold(result, op);
        }
        return result;
    }
}
