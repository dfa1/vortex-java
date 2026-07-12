package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ChunkedBoolArray;
import io.github.dfa1.vortex.reader.array.ChunkedByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedDoubleArray;
import io.github.dfa1.vortex.reader.array.ChunkedFloatArray;
import io.github.dfa1.vortex.reader.array.ChunkedIntArray;
import io.github.dfa1.vortex.reader.array.ChunkedLongArray;
import io.github.dfa1.vortex.reader.array.ChunkedShortArray;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/// Built-in decoder for the `vortex.chunked` layout — a sequence of flat leaves decoded into the
/// zero-copy `ChunkedXxxArray` shape (ADR 0012). Extracted verbatim from `ScanIterator`'s
/// `collectFlats` + `decodeChunkedLayout`.
final class ChunkedLayoutDecoder implements LayoutDecoder {

    @Override
    public LayoutId layoutId() {
        return LayoutId.CHUNKED;
    }

    @Override
    public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
        var flats = new ArrayList<Layout>();
        collectFlats(layout, flats);
        return decodeChunkedLayout(ctx, flats, dtype, layout.rowCount());
    }

    /// Concatenates the per-chunk validity of masked chunks into one row-level bitmap, or returns
    /// `null` when no chunk is nullable. Unmasked chunks (and masked chunks with a `null` validity,
    /// which mean all-valid) contribute all-valid rows. This preserves nullability that the
    /// `ChunkedXxxArray.of` constructors deliberately drop when flattening masked chunks — needed so
    /// a nullable chunked column (e.g. the codes of a nullable global-dict column) keeps its nulls.
    ///
    /// @param chunkArrays the decoded per-chunk arrays, in row order
    /// @param totalRows    the total logical row count across all chunks
    /// @param arena        allocator for the combined bitmap
    /// @return a bit-packed row-validity [BoolArray] of `totalRows` bits, or `null` if all valid
    private static BoolArray combineChunkValidity(List<Array> chunkArrays, long totalRows,
            SegmentAllocator arena) {
        boolean anyMasked = false;
        for (Array chunk : chunkArrays) {
            if (chunk instanceof MaskedArray m && m.validity() != null) {
                anyMasked = true;
                break;
            }
        }
        if (!anyMasked) {
            return null;
        }
        MemorySegment bits = arena.allocate((totalRows + 7) >>> 3);
        long row = 0;
        for (Array chunk : chunkArrays) {
            long chunkLen = chunk.length();
            BoolArray validity = chunk instanceof MaskedArray m ? m.validity() : null;
            for (long i = 0; i < chunkLen; i++) {
                if (validity == null || validity.getBoolean(i)) {
                    long globalRow = row + i;
                    long byteIdx = globalRow >>> 3;
                    byte cur = bits.get(ValueLayout.JAVA_BYTE, byteIdx);
                    bits.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) ((cur & 0xff) | (1 << (globalRow & 7))));
                }
            }
            row += chunkLen;
        }
        return new MaterializedBoolArray(DType.BOOL, totalRows, bits.asReadOnly());
    }

    /// Flattens a layout subtree into its ordered flat (and dict) leaves. A private copy of
    /// `ScanIterator.collectFlats`: the scan keeps its own for chunk-shape planning, while the
    /// chunked decoder needs the same flattening to build the leaf array list.
    ///
    /// @param layout the layout subtree to flatten
    /// @param out    accumulator for the flat leaves in scan order
    private static void collectFlats(Layout layout, List<Layout> out) {
        if (layout.isFlat()) {
            out.add(layout);
        } else if (layout.isDict()) {
            // Dict layout is a leaf chunk — decoded as a unit (values + codes).
            out.add(layout);
        } else if (layout.isZoned()) {
            // vortex.stats wraps one child (the data layout) — pass through for data
            if (!layout.children().isEmpty()) {
                collectFlats(layout.children().getFirst(), out);
            }
        } else if (layout.isChunked()) {
            // metadata[0] == 1 means children[0] is the per-chunk stats layout; skip it
            int start = (layout.metadata() != null
                                 && layout.metadata().byteSize() > 0
                                 && layout.metadata().get(ValueLayout.JAVA_BYTE, 0) == 1) ? 1 : 0;
            for (int i = start; i < layout.children().size(); i++) {
                collectFlats(layout.children().get(i), out);
            }
        }
    }

    private static Array decodeChunkedLayout(LayoutDecodeContext ctx, List<Layout> flats, DType dtype,
            long totalRows) {
        if (flats.isEmpty()) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED, "no flat children");
        }
        if (flats.size() == 1) {
            return ctx.decodeChild(flats.getFirst(), dtype);
        }
        // ADR 0012: every primitive ptype gets the zero-copy ChunkedXxxArray shape.
        // The concat path is gone.
        var chunkArrays = new ArrayList<Array>(flats.size());
        for (Layout flat : flats) {
            // Registry dispatch, not a direct decodeFlat call — a custom decoder registered for
            // a leaf's layout id must be honored under a chunked parent too.
            chunkArrays.add(ctx.decodeChild(flat, dtype));
        }
        Array data;
        if (dtype instanceof DType.Bool) {
            data = ChunkedBoolArray.of(dtype, totalRows, chunkArrays);
        } else if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            data = VarBinArray.ChunkedMode.of(dtype, totalRows, chunkArrays);
        } else {
            PType ptype = ((DType.Primitive) dtype).ptype();
            data = switch (ptype) {
                case I64, U64 -> ChunkedLongArray.of(dtype, totalRows, chunkArrays);
                case I32, U32 -> ChunkedIntArray.of(dtype, totalRows, chunkArrays);
                case F64 -> ChunkedDoubleArray.of(dtype, totalRows, chunkArrays);
                case F32 -> ChunkedFloatArray.of(dtype, totalRows, chunkArrays);
                case I16, U16 -> ChunkedShortArray.of(dtype, totalRows, chunkArrays);
                case I8, U8 -> ChunkedByteArray.of(dtype, totalRows, chunkArrays);
                default -> throw new VortexException("unsupported ptype for chunked layout: " + ptype);
            };
        }
        // The ChunkedXxxArray.of constructors flatten masked chunks to their inner data, dropping
        // validity. Recover per-chunk nullability by concatenating chunk validity into one bitmap and
        // re-wrapping — otherwise a nullable chunked column (e.g. nullable global-dict codes) loses
        // its nulls when it spans more than one chunk.
        BoolArray validity = combineChunkValidity(chunkArrays, totalRows, ctx.arena());
        return validity != null ? new MaskedArray(data, validity) : data;
    }
}
