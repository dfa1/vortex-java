package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Multi-chunk {@link LongArray} view backed by an immutable array of child
/// chunks plus their cumulative row offsets. Scalar access is stateless —
/// {@link #getLong(long)} resolves the chunk index via {@link #findChunk(long[], long)}
/// (a binary search over {@code offsets}) on every call.
///
/// Per ADR 0012, this preserves zero-copy on multi-chunk reads: each chunk
/// remains its own underlying array (typically an mmap slice), no
/// concatenation occurs.
///
/// @param dtype    logical element type
/// @param length   total logical row count = {@code offsets[children.length]}
/// @param children chunk arrays in scan order
/// @param offsets  cumulative row counts; {@code offsets[i] = sum of children[0..i).length()}
///                 with {@code offsets[0] = 0} and {@code offsets[children.length] = length}
public record ChunkedLongArray(DType dtype, long length, LongArray[] children, long[] offsets) implements LongArray {

    /// Builds a {@link ChunkedLongArray} from a list of chunk arrays. Nested
    /// chunked arrays are flattened; {@link MaskedArray} chunks are unwrapped
    /// to their inner data (validity dropped — matches prior concat behaviour).
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count across all chunks
    /// @param chunks    non-empty list of chunk arrays
    /// @return a new {@link ChunkedLongArray} over the flattened chunks
    /// @throws VortexException on empty input, non-{@link LongArray} chunks, or row-count mismatch
    public static ChunkedLongArray of(DType dtype, long totalRows, List<? extends Array> chunks) {
        if (chunks.isEmpty()) {
            throw new VortexException("ChunkedLongArray: empty chunk list");
        }
        var typed = new ArrayList<LongArray>(chunks.size());
        for (Array c : chunks) {
            flatten(c, typed);
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("ChunkedLongArray: chunk rows sum to " + off[off.length - 1]
                    + ", expected " + totalRows);
        }
        return new ChunkedLongArray(dtype, totalRows, typed.toArray(LongArray[]::new), off);
    }

    private static void flatten(Array chunk, List<LongArray> out) {
        Array data = chunk instanceof MaskedArray m ? m.inner() : chunk;
        if (data instanceof ChunkedLongArray nested) {
            for (LongArray child : nested.children) {
                out.add(child);
            }
        } else if (data instanceof LongArray la) {
            out.add(la);
        } else {
            throw new VortexException("ChunkedLongArray: chunk is not a LongArray: "
                    + data.getClass().getSimpleName());
        }
    }

    /// Binary-search {@code offsets} to find the chunk index containing row {@code i}.
    ///
    /// @param offsets cumulative row offsets (length = nchunks + 1)
    /// @param i       global row index
    /// @return chunk index in {@code [0, nchunks)}
    public static int findChunk(long[] offsets, long i) {
        int hit = Arrays.binarySearch(offsets, i);
        int idx = hit >= 0 ? hit : -hit - 2;
        if (idx >= offsets.length - 1) {
            idx = offsets.length - 2;
        }
        return idx;
    }

    @Override
    public long getLong(long i) {
        int c = findChunk(offsets, i);
        return children[c].getLong(i - offsets[c]);
    }

    @Override
    public void forEachLong(LongConsumer cons) {
        for (LongArray child : children) {
            child.forEachLong(cons);
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long result = identity;
        for (LongArray child : children) {
            result = child.fold(result, op);
        }
        return result;
    }
}
