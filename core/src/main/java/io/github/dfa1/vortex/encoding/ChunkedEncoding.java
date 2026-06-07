package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.StructArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// Encoding for {@code vortex.chunked} — a segment-level chunked array.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Metadata: empty bytes
///   <li>Buffers: 0
///   <li>Children: N+1 — {@code children[0]} is the chunk offsets (U64, non-nullable,
///       length = nchunks+1, cumulative from 0); {@code children[1..N]} are the chunk arrays.
/// </ul>
public final class ChunkedEncoding implements Encoding {

    /// Creates a new {@code ChunkedEncoding} instance.
    public ChunkedEncoding() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, (ChunkedData) data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static final List<Encoding> FALLBACK = List.of(
                new PrimitiveEncoding(), new VarBinEncoding(), new BoolEncoding(),
                new NullEncoding(), new ByteBoolEncoding(), new StructEncoding());

        static EncodeResult encode(DType dtype, ChunkedData data, EncodeContext ctx) {
            List<Object> chunks = data.chunks();
            long[] chunkLengths = data.chunkLengths();
            int nchunks = chunks.size();
            if (nchunks == 0) {
                throw new VortexException(EncodingId.VORTEX_CHUNKED, "at least one chunk required");
            }

            // Build cumulative offsets: [0, len0, len0+len1, ...]
            long[] offsets = new long[nchunks + 1];
            offsets[0] = 0;
            for (int i = 0; i < nchunks; i++) {
                offsets[i + 1] = offsets[i] + chunkLengths[i];
            }

            // Encode offsets child (U64 primitive)
            DType u64 = new DType.Primitive(PType.U64, false);
            EncodeResult offsetsResult = ctx.lookupEncoding(EncodingId.VORTEX_PRIMITIVE).encode(u64, offsets, ctx);

            List<MemorySegment> allBuffers = new ArrayList<>(offsetsResult.buffers());
            EncodeNode[] children = new EncodeNode[nchunks + 1];
            children[0] = offsetsResult.rootNode();

            // Encode each chunk
            Encoding inner = findEncoding(dtype);
            for (int i = 0; i < nchunks; i++) {
                EncodeResult chunkResult = inner.encode(dtype, chunks.get(i), ctx);
                int bufOffset = allBuffers.size();
                children[i + 1] = EncodeNode.remapBufferIndices(chunkResult.rootNode(), bufOffset);
                allBuffers.addAll(chunkResult.buffers());
            }

            EncodeNode root = new EncodeNode(
                    EncodingId.VORTEX_CHUNKED,
                    ByteBuffer.wrap(new byte[0]),
                    children,
                    new int[]{});
            return new EncodeResult(root, List.copyOf(allBuffers), null, null);
        }

        private static Encoding findEncoding(DType dtype) {
            for (Encoding enc : FALLBACK) {
                if (enc.accepts(dtype)) {
                    return enc;
                }
            }
            throw new UnsupportedOperationException("no fallback encoding for dtype: " + dtype);
        }
    }

    private static final class Decoder {

        private static final ValueLayout.OfLong LE_LONG =
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        static Array decode(DecodeContext ctx) {
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
            Array offsetsArray = ctx.decodeChild(0, u64, nchunks + 1L);
            MemorySegment offsetsBuf = offsetsArray.buffer(0);
            long[] offsets = new long[nchunks + 1];
            for (int i = 0; i <= nchunks; i++) {
                offsets[i] = offsetsBuf.get(LE_LONG, (long) i * 8);
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
                MemorySegment src = chunk.buffer(0);
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
}
