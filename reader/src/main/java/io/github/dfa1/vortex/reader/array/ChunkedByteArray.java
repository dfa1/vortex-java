package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongBinaryOperator;

/// Multi-chunk [ByteArray] record. ADR 0012 shape.
///
/// @param dtype    logical element type (I8 or U8)
/// @param length   total logical row count
/// @param children chunk arrays in scan order
/// @param offsets  cumulative row counts; length = `children.length + 1`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ChunkedByteArray(DType dtype, long length, ByteArray[] children, long[] offsets) implements ByteArray {

    /// Builds a [ChunkedByteArray].
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new [ChunkedByteArray]
    /// @throws VortexException on empty input, non-[ByteArray] chunks, or row-count mismatch
    public static ChunkedByteArray of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedByteArray: empty chunk list");
        }
        var typed = new ArrayList<ByteArray>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedByteArray: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedByteArray(dtype, totalRows, typed.toArray(ByteArray[]::new), off);
    }

    private static void flatten(Array chunk, List<ByteArray> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedByteArray nested) {
            Collections.addAll(out, nested.children);
        } else if (data instanceof ByteArray ba) {
            out.add(ba);
        } else {
            throw new VortexException("ChunkedByteArray: chunk is not a ByteArray: "
                    + data.getClass().getSimpleName());
        }
    }

    @Override
    public byte getByte(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getByte(i - offsets[c]);
    }

    @Override
    public int getInt(long i) {
        int c = ChunkedLongArray.findChunk(offsets, i);
        return children[c].getInt(i - offsets[c]);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long result = identity;
        for (ByteArray child : children) {
            result = child.fold(result, op);
        }
        return result;
    }

    @Override
    public void forEachByte(ByteConsumer c) {
        for (ByteArray child : children) {
            child.forEachByte(c);
        }
    }

    @Override
    public Array limited(long rows) {
        return ChunkedByteArray.of(dtype, rows, ChunkedArrays.limitedChildren(children, offsets, rows));
    }
}
