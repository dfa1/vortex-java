package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongBinaryOperator;

/// Multi-chunk {@link ShortArray} record. ADR 0012 shape — children hold their
/// own segments; scalar access binary-searches `offsets`.
///
/// @param dtype    logical element type (I16 or U16)
/// @param length   total logical row count
/// @param children chunk arrays in scan order
/// @param offsets  cumulative row counts; length = `children.length + 1`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ChunkedShortArray(DType dtype, long length, ShortArray[] children, long[] offsets) implements ShortArray {

    /// Builds a {@link ChunkedShortArray}.
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new {@link ChunkedShortArray}
    /// @throws VortexException on empty input, non-{@link ShortArray} chunks, or row-count mismatch
    public static ChunkedShortArray of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedShortArray: empty chunk list");
        }
        var typed = new ArrayList<ShortArray>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedShortArray: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedShortArray(dtype, totalRows, typed.toArray(ShortArray[]::new), off);
    }

    private static void flatten(Array chunk, List<ShortArray> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedShortArray nested) {
            Collections.addAll(out, nested.children);
        } else if (data instanceof ShortArray sa) {
            out.add(sa);
        } else {
            throw new VortexException("ChunkedShortArray: chunk is not a ShortArray: "
                    + data.getClass().getSimpleName());
        }
    }

    @Override
    public short getShort(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getShort(i - offsets[c]);
    }

    @Override
    public int getInt(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getInt(i - offsets[c]);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long result = identity;
        for (ShortArray child : children) {
            result = child.fold(result, op);
        }
        return result;
    }

    @Override
    public void forEachShort(ShortConsumer c) {
        for (ShortArray child : children) {
            child.forEachShort(c);
        }
    }
}
