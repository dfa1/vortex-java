package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Multi-chunk {@link DoubleArray} view; record per ADR 0012 with stateless
/// {@link ChunkedLongArray#findChunk(long[], long)} dispatch.
///
/// @param dtype    logical element type
/// @param length   total logical row count
/// @param children chunk arrays in scan order
/// @param offsets  cumulative row counts; length = `children.length + 1`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ChunkedDoubleArray(DType dtype, long length, DoubleArray[] children, long[] offsets) implements DoubleArray {

    /// Builds a {@link ChunkedDoubleArray}. Flattens nested chunked arrays;
    /// unwraps {@link MaskedArray} chunks.
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new {@link ChunkedDoubleArray}
    /// @throws VortexException on empty input, non-{@link DoubleArray} chunks, or row-count mismatch
    public static ChunkedDoubleArray of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedDoubleArray: empty chunk list");
        }
        var typed = new ArrayList<DoubleArray>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedDoubleArray: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedDoubleArray(dtype, totalRows, typed.toArray(DoubleArray[]::new), off);
    }

    private static void flatten(Array chunk, List<DoubleArray> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedDoubleArray nested) {
            Collections.addAll(out, nested.children);
        } else if (data instanceof DoubleArray da) {
            out.add(da);
        } else {
            throw new VortexException("ChunkedDoubleArray: chunk is not a DoubleArray: "
                    + data.getClass().getSimpleName());
        }
    }

    @Override
    public double getDouble(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getDouble(i - offsets[c]);
    }

    @Override
    public void forEachDouble(DoubleConsumer cons) {
        for (DoubleArray child : children) {
            child.forEachDouble(cons);
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        double result = identity;
        for (DoubleArray child : children) {
            result = child.fold(result, op);
        }
        return result;
    }

    @Override
    public Array limited(long rows) {
        return ChunkedDoubleArray.of(dtype, rows, ChunkedArrays.limitedChildren(children, offsets, rows));
    }
}
