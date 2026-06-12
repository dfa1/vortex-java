package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.encoding.EncodingId;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// Read-only decoder for {@code vortex.chunked}.
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
        return dtype instanceof DType.Primitive || dtype instanceof DType.Struct;
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

        return concat(chunks, dtype, ctx.rowCount(), ctx.arena());
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

    private static Array concat(List<Array> chunks, DType dtype, long totalRows, SegmentAllocator arena) {
        if (dtype instanceof DType.Primitive pt) {
            return concatPrimitive(chunks, pt, dtype, totalRows, arena);
        }
        if (dtype instanceof DType.Struct struct) {
            return concatStruct(chunks, struct, totalRows, arena);
        }
        throw new VortexException(EncodingId.VORTEX_CHUNKED,
                "concat not supported for dtype: " + dtype);
    }

    private static Array concatPrimitive(
            List<Array> chunks, DType.Primitive pt, DType dtype, long totalRows, SegmentAllocator arena
    ) {
        PType ptype = pt.ptype();
        MemorySegment combined = arena.allocate(totalRows * ptype.byteSize());
        long byteOffset = 0;
        for (Array chunk : chunks) {
            MemorySegment src = ArraySegments.of(chunk);
            MemorySegment.copy(src, 0, combined, byteOffset, src.byteSize());
            byteOffset += src.byteSize();
        }
        MemorySegment ro = combined.asReadOnly();
        return switch (ptype) {
            case I64, U64 -> new LongArray(dtype, totalRows, ro);
            case I32, U32 -> new IntArray(dtype, totalRows, ro);
            case F64 -> new DoubleArray(dtype, totalRows, ro);
            case F32 -> new FloatArray(dtype, totalRows, ro);
            case I16, U16 -> new ShortArray(dtype, totalRows, ro);
            case I8, U8 -> new ByteArray(dtype, totalRows, ro);
            default -> throw new VortexException(EncodingId.VORTEX_CHUNKED,
                    "unsupported ptype for concat: " + ptype);
        };
    }

    private static StructArray concatStruct(
            List<Array> chunks, DType.Struct struct, long totalRows, SegmentAllocator arena
    ) {
        int nfields = struct.fieldTypes().size();
        List<Array> concatFields = new ArrayList<>(nfields);
        for (int f = 0; f < nfields; f++) {
            DType fieldDtype = struct.fieldTypes().get(f);
            List<Array> fieldChunks = new ArrayList<>(chunks.size());
            for (Array chunk : chunks) {
                fieldChunks.add(((StructArray) chunk).field(f));
            }
            concatFields.add(concat(fieldChunks, fieldDtype, totalRows, arena));
        }
        return new StructArray(struct, totalRows, concatFields);
    }
}
