package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
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
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// Read-only decoder for `vortex.chunked`.
public final class ChunkedEncodingDecoder implements EncodingDecoder {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ChunkedEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_CHUNKED;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive
                || dtype instanceof DType.Bool
                || dtype instanceof DType.Struct;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        int nchildren = ctx.node().children().length;
        if (nchildren < 1) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED,
                    "needs at least one child (chunk offsets)");
        }
        int nchunks = nchildren - 1;
        long[] offsets = readOffsets(ctx, nchunks);

        DType dtype = ctx.dtype();
        List<Array> chunks = new ArrayList<>(nchunks);
        for (int i = 0; i < nchunks; i++) {
            long chunkLen = offsets[i + 1] - offsets[i];
            chunks.add(ctx.decodeChild(i + 1, dtype, chunkLen));
        }

        return wrap(chunks, dtype, ctx.rowCount());
    }

    private static long[] readOffsets(DecodeContext ctx, int nchunks) {
        DType u64 = new DType.Primitive(PType.U64, false);
        MemorySegment offsetsBuf = ctx.decodeChildSegment(0, u64, nchunks + 1L);
        long cap = SegmentBroadcast.capacity(offsetsBuf, 8);
        long[] offsets = new long[nchunks + 1];
        for (int i = 0; i <= nchunks; i++) {
            offsets[i] = offsetsBuf.get(LE_LONG, (i % cap) * 8);
        }
        return offsets;
    }

    /// Wraps the decoded chunk children in a zero-copy view: a `ChunkedXxxArray`
    /// for primitives and Bool, a {@link StructArray} of per-field chunked views for
    /// {@link DType.Struct}. No concat / no per-row materialise.
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
            // Each chunk decoded as Variant materialises to its inner-typed constant array
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
