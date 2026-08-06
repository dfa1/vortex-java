package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Multi-chunk [VarBinArray] — wraps a list of child `VarBinArray`s plus
/// cumulative row offsets. Per-row accessors binary-search `offsets` to find the
/// owning chunk and delegate. Per ADR 0012, preserves zero-copy on multi-chunk Utf8 /
/// Binary columns: each chunk's underlying segments stay live (mmap slices); no concat.
///
/// [#bytesSegment()] is the [MemorySegment#NULL] sentinel — chunked
/// arrays have no single contiguous bytes segment. Callers that need contiguous
/// bytes must materialize via the chunked children.
///
/// @param dtype    logical element type (Utf8 or Binary)
/// @param length   total logical row count
/// @param children chunk arrays in scan order; each is itself a [VarBinArray]
/// @param offsets  cumulative row counts; length = `children.length + 1`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record VarBinChunkedArray(DType dtype, long length, VarBinArray[] children, long[] offsets)
        implements VarBinArray {

    /// Builds a `VarBinChunkedArray` from a list of chunk arrays that are already all typed
    /// [VarBinArray]s (no all-null [NullArray] chunks). Used by [#limited(long)],
    /// whose children are always concrete `VarBinArray`s.
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of [VarBinArray] chunks
    /// @return a new `VarBinChunkedArray`
    /// @throws VortexException on empty input, non-[VarBinArray] chunks, or row-count mismatch
    public static VarBinChunkedArray of(DType dtype, long totalRows,
            java.util.List<? extends Array> chunks) {
        return of(dtype, totalRows, chunks, null);
    }

    /// Builds a `VarBinChunkedArray` from a list of chunk arrays.
    ///
    /// An entirely-null chunk decodes to a [NullArray] rather than a [VarBinArray]
    /// (e.g. a `vortex.null` flat, or `vortex.constant` with a null scalar, #269).
    /// Such a chunk is materialized into an all-null [VarBinOffsetArray] of the same row
    /// count — every row zero-length, all offsets zero — so the chunked column keeps
    /// a uniform `VarBinArray` shape. Row-level nullability is preserved separately by
    /// the caller's validity bitmap.
    ///
    /// @param dtype     logical element type
    /// @param totalRows expected total row count
    /// @param chunks    non-empty list of chunk arrays; each a [VarBinArray] or [NullArray]
    /// @param arena     allocator for the offsets segment of a materialized null chunk;
    ///                  may be `null` only when no chunk is a [NullArray]
    /// @return a new `VarBinChunkedArray`
    /// @throws VortexException on empty input, non-`VarBinArray`/`NullArray` chunks,
    ///                         or row-count mismatch
    public static VarBinChunkedArray of(DType dtype, long totalRows,
            java.util.List<? extends Array> chunks, SegmentAllocator arena) {
        if (chunks.isEmpty()) {
            throw new VortexException("VarBinChunkedArray: empty chunk list");
        }
        var typed = new java.util.ArrayList<VarBinArray>(chunks.size());
        for (Array c : chunks) {
            Array data = c instanceof MaskedArray m ? m.inner() : c;
            if (data instanceof VarBinChunkedArray nested) {
                java.util.Collections.addAll(typed, nested.children);
            } else if (data instanceof VarBinArray vb) {
                typed.add(vb);
            } else if (data instanceof NullArray na) {
                if (arena == null) {
                    throw new VortexException(
                            "VarBinChunkedArray: null chunk requires an allocator");
                }
                typed.add(allNull(dtype, na.length(), arena));
            } else {
                throw new VortexException("VarBinChunkedArray: chunk is not a VarBinArray: "
                        + data.getClass().getSimpleName());
            }
        }
        long[] off = new long[typed.size() + 1];
        for (int i = 0; i < typed.size(); i++) {
            off[i + 1] = off[i] + typed.get(i).length();
        }
        if (off[off.length - 1] != totalRows) {
            throw new VortexException("VarBinChunkedArray: chunk rows sum to "
                    + off[off.length - 1] + ", expected " + totalRows);
        }
        return new VarBinChunkedArray(dtype, totalRows, typed.toArray(VarBinArray[]::new), off);
    }

    /// Builds an all-null [VarBinOffsetArray] of `n` rows: an empty bytes segment and an
    /// offsets segment of `n + 1` zeros, so every row is zero-length. Row nullability
    /// is carried by the caller's validity bitmap, not the byte data.
    ///
    /// @param dtype logical element type (Utf8 or Binary)
    /// @param n     number of all-null rows
    /// @param arena allocator for the offsets segment
    /// @return a [VarBinOffsetArray] with `n` zero-length rows
    private static VarBinOffsetArray allNull(DType dtype, long n, SegmentAllocator arena) {
        MemorySegment offsets = arena.allocate((n + 1) * Long.BYTES, Long.BYTES);
        return new VarBinOffsetArray(dtype, n, MemorySegment.NULL, offsets, PType.I64);
    }

    private int findChunk(long i) {
        int hit = java.util.Arrays.binarySearch(offsets, i);
        int idx = hit >= 0 ? hit : -hit - 2;
        if (idx >= children.length) {
            idx = children.length - 1;
        }
        return idx;
    }

    @Override
    public MemorySegment bytesSegment() {
        return MemorySegment.NULL;
    }

    /// No single contiguous segment — chunked data lives across children.
    ///
    /// @return always empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.empty();
    }

    @Override
    public byte[] getBytes(long i) {
        int c = findChunk(i);
        return children[c].getBytes(i - offsets[c]);
    }

    @Override
    public String getString(long i) {
        int c = findChunk(i);
        return children[c].getString(i - offsets[c]);
    }

    @Override
    public int getByteLength(long i) {
        int c = findChunk(i);
        return children[c].getByteLength(i - offsets[c]);
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        for (VarBinArray child : children) {
            child.forEachByteLength(c);
        }
    }

    @Override
    public VarBinArray limited(long rows) {
        if (rows >= length) {
            return this;
        }
        // Keep full children that fit, recursively limited the boundary child.
        var kept = new java.util.ArrayList<Array>(children.length);
        for (int i = 0; i < children.length; i++) {
            long start = offsets[i];
            long end = offsets[i + 1];
            if (start >= rows) {
                break;
            }
            if (end <= rows) {
                kept.add(children[i]);
            } else {
                kept.add(children[i].limited(rows - start));
            }
        }
        return VarBinChunkedArray.of(dtype, rows, kept);
    }
}
