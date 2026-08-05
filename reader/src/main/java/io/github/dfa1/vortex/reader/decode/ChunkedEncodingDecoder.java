package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ChunkedBoolArray;
import io.github.dfa1.vortex.reader.array.ChunkedByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedDoubleArray;
import io.github.dfa1.vortex.reader.array.ChunkedFloatArray;
import io.github.dfa1.vortex.reader.array.ChunkedIntArray;
import io.github.dfa1.vortex.reader.array.ChunkedLongArray;
import io.github.dfa1.vortex.reader.array.ChunkedShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;

/// Read-only decoder for `vortex.chunked`.
public final class ChunkedEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_CHUNKED;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        int nchildren = ctx.node().children().length;
        if (nchildren < 1) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED,
                    "needs at least one child (chunk offsets)");
        }
        int nchunks = nchildren - 1;
        // A chunked node with only the offsets child carries no data at all, so it can never
        // describe a non-empty column. Rejecting it here names the real defect uniformly;
        // letting it through would surface as the less direct `Chunked*Array` "empty chunk
        // list" error for most dtypes, and as a silently empty zero-field struct for
        // DType.Struct (any struct with a field routes through the same "empty chunk list").
        if (nchunks == 0 && ctx.rowCount() > 0) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED,
                    "no chunks for " + ctx.rowCount() + " row(s)");
        }
        long[] offsets = readOffsets(ctx, nchunks, ctx.rowCount());

        DType dtype = ctx.dtype();
        List<Array> chunks = new ArrayList<>(nchunks);
        for (int i = 0; i < nchunks; i++) {
            long chunkLen = offsets[i + 1] - offsets[i];
            chunks.add(ctx.decodeChild(i + 1, dtype, chunkLen));
        }

        return wrap(chunks, dtype, ctx.rowCount());
    }

    /// Reads the `nchunks + 1` cumulative chunk offsets and validates them.
    ///
    /// The offsets buffer is untrusted: an empty one would make the broadcast wrap divide by
    /// zero, and a non-monotonic or negative pair yields a negative chunk length that flows
    /// into the child decode and then into a `Chunked*Array` whose `offsets` are no longer
    /// sorted — making its binary-search dispatch index a chunk at a negative row. Both must
    /// fail as a [VortexException], never as an `ArithmeticException` or a raw
    /// `IndexOutOfBoundsException` (ADR 0003). The scan is O(nchunks), not per row.
    ///
    /// The final offset is also checked against `rowCount`: without this, an offsets pair like
    /// `[0, 1 << 40]` for a 4-row array passes the monotonic check yet hands a terabyte-sized
    /// chunk length to `decodeChild`, which allocates it before anything downstream can reject
    /// it — an `OutOfMemoryError` rather than a `VortexException` (ADR 0003).
    ///
    /// @param ctx      decode context
    /// @param nchunks  number of data chunks (children minus the offsets child)
    /// @param rowCount logical row count the chunk offsets must sum to
    /// @return the `nchunks + 1` monotonic, non-negative offsets spanning exactly `rowCount`
    private static long[] readOffsets(DecodeContext ctx, int nchunks, long rowCount) {
        MemorySegment offsetsBuf = ctx.decodeChildSegment(0, DType.U64, nchunks + 1L);
        long[] offsets = new long[nchunks + 1];
        for (int i = 0; i <= nchunks; i++) {
            offsets[i] = offsetsBuf.get(LE_LONG, SegmentBroadcast.elementOffset(offsetsBuf, i, 8));
            long previous = i == 0 ? 0 : offsets[i - 1];
            if (offsets[i] < previous) {
                throw new VortexException(EncodingId.VORTEX_CHUNKED,
                        "chunk offsets must be non-negative and non-decreasing, got offsets["
                                + i + "]=" + offsets[i] + " after " + previous);
            }
        }
        long span = offsets[nchunks] - offsets[0];
        if (span != rowCount) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED,
                    "chunk offsets span " + span + " rows, expected " + rowCount);
        }
        return offsets;
    }

    /// Wraps the decoded chunk children in a zero-copy view: a `ChunkedXxxArray`
    /// for primitives and Bool, a [StructArray] of per-field chunked views for
    /// [DType.Struct]. No concat / no per-row materialize.
    private static Array wrap(List<Array> chunks, DType dtype, long totalRows) {
        if (dtype instanceof DType.Primitive pt) {
            return wrapPrimitive(chunks, pt, dtype, totalRows);
        }
        if (dtype instanceof DType.Bool) {
            return ChunkedBoolArray.of(dtype, totalRows, chunks);
        }
        if (dtype instanceof DType.Struct struct) {
            return wrapStruct(chunks, struct, totalRows);
        }
        if (dtype instanceof DType.Variant) {
            // Each chunk decoded as Variant materializes to its inner-typed constant array
            // (see ConstantEncodingDecoder). Wrap the chunks under that inner dtype; the
            // VariantArray container re-applies the logical Variant dtype.
            if (chunks.isEmpty()) {
                throw new VortexException(EncodingId.VORTEX_CHUNKED, "chunked variant has no chunks");
            }
            DType innerDtype = chunks.get(0).dtype();
            if (innerDtype instanceof DType.Primitive innerPt) {
                return wrapPrimitive(chunks, innerPt, innerDtype, totalRows);
            }
            if (innerDtype instanceof DType.Bool) {
                return ChunkedBoolArray.of(innerDtype, totalRows, chunks);
            }
            throw new VortexException(EncodingId.VORTEX_CHUNKED,
                    "chunked variant inner dtype not supported: " + innerDtype);
        }
        throw new VortexException(EncodingId.VORTEX_CHUNKED,
                "chunked not supported for dtype: " + dtype);
    }

    private static Array wrapPrimitive(
            List<Array> chunks, DType.Primitive pt, DType dtype, long totalRows
    ) {
        PType ptype = pt.ptype();
        return switch (ptype) {
            case I64, U64 -> ChunkedLongArray.of(dtype, totalRows, chunks);
            case I32, U32 -> ChunkedIntArray.of(dtype, totalRows, chunks);
            case F64 -> ChunkedDoubleArray.of(dtype, totalRows, chunks);
            case F32 -> ChunkedFloatArray.of(dtype, totalRows, chunks);
            case I16, U16 -> ChunkedShortArray.of(dtype, totalRows, chunks);
            case I8, U8 -> ChunkedByteArray.of(dtype, totalRows, chunks);
            default -> throw new VortexException(EncodingId.VORTEX_CHUNKED,
                    "unsupported ptype for chunked: " + ptype);
        };
    }

    private static StructArray wrapStruct(List<Array> chunks, DType.Struct struct, long totalRows) {
        int nfields = struct.fieldTypes().size();
        List<Array> wrappedFields = new ArrayList<>(nfields);
        for (int f = 0; f < nfields; f++) {
            DType fieldDtype = struct.fieldTypes().get(f);
            List<Array> fieldChunks = new ArrayList<>(chunks.size());
            for (Array chunk : chunks) {
                fieldChunks.add(((StructArray) chunk).field(f));
            }
            wrappedFields.add(wrap(fieldChunks, fieldDtype, totalRows));
        }
        return new StructArray(struct, totalRows, wrappedFields);
    }
}
