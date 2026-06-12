package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.RLEMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code fastlanes.rle}.
public final class RleEncodingDecoder implements EncodingDecoder {

    private static final int FL_CHUNK_SIZE = 1024;

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public RleEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_RLE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && !p.ptype().isFloating();
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        RLEMetadata meta;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            meta = RLEMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.FASTLANES_RLE, "invalid metadata", e);
        }

        long valuesLen = meta.values_len();
        long indicesLen = meta.indices_len();
        PType indicesPtype = PType.fromOrdinal(meta.indices_ptype().value());
        long offsetsLen = meta.values_idx_offsets_len();
        PType offsetsPtype = PType.fromOrdinal(meta.values_idx_offsets_ptype().value());
        int offset = (int) meta.offset();

        long rowCount = ctx.rowCount();
        if (rowCount == 0 || indicesLen == 0) {
            return emptyArray(ctx);
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_RLE, "expected Primitive dtype, got " + ctx.dtype());
        }
        PType ptype = p.ptype();

        DType valuesDtype = new DType.Primitive(ptype, false);
        DType indicesDtype = new DType.Primitive(indicesPtype, false);
        DType offsetsDtype = new DType.Primitive(offsetsPtype, false);

        Array indicesRaw = ctx.decodeChild(1, indicesDtype, indicesLen);

        BoolArray indicesValidity = null;
        Array indicesArr = indicesRaw;
        if (indicesRaw instanceof MaskedArray masked) {
            indicesArr = masked.inner();
            indicesValidity = masked.validity();
        }

        long[] values = readLongs(ctx.decodeChildSegment(0, valuesDtype, valuesLen), (int) valuesLen, ptype);
        int[] indices = readIndices(ArraySegments.of(indicesArr), (int) indicesLen, indicesPtype);
        long[] valuesIdxOffsets = readUnsignedLongs(ctx.decodeChildSegment(2, offsetsDtype, offsetsLen), (int) offsetsLen, offsetsPtype);

        int numChunks = (int) (indicesLen / FL_CHUNK_SIZE);
        int chunkEnd = (int) ((offset + rowCount + FL_CHUNK_SIZE - 1) / FL_CHUNK_SIZE);
        chunkEnd = Math.min(chunkEnd, numChunks);

        long[] decoded = new long[chunkEnd * FL_CHUNK_SIZE];
        long firstOffset = valuesLen > 0 ? valuesIdxOffsets[0] : 0L;

        for (int chunkIdx = 0; chunkIdx < chunkEnd; chunkIdx++) {
            long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
            long nextValueIdxOffset = (chunkIdx + 1 < numChunks)
                                              ? (valuesIdxOffsets[chunkIdx + 1] - firstOffset)
                                              : valuesLen;
            int numChunkValues = (int) (nextValueIdxOffset - valueIdxOffset);

            int chunkBase = chunkIdx * FL_CHUNK_SIZE;
            if (numChunkValues <= 1) {
                long fillVal = numChunkValues == 1 ? values[(int) valueIdxOffset] : 0L;
                for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                    decoded[chunkBase + i] = fillVal;
                }
            } else {
                for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                    int idx = indices[chunkBase + i];
                    if (idx >= numChunkValues) {
                        idx = numChunkValues - 1;
                    }
                    decoded[chunkBase + i] = values[(int) valueIdxOffset + idx];
                }
            }
        }

        MemorySegment seg = fromLongs(decoded, offset, (int) rowCount, ptype, ctx.arena());
        Array result = toArray(ctx.dtype(), rowCount, seg, ptype);
        if (indicesValidity == null) {
            return result;
        }
        int validityBytes = (int) ((rowCount + 7) / 8);
        MemorySegment validityBuf = ctx.arena().allocate(validityBytes);
        for (long j = 0; j < rowCount; j++) {
            if (indicesValidity.getBoolean(offset + j)) {
                int byteIdx = (int) (j >>> 3);
                byte current = validityBuf.get(ValueLayout.JAVA_BYTE, byteIdx);
                validityBuf.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (current | (1 << (j & 7))));
            }
        }
        BoolArray outputValidity = new BoolArray(new DType.Bool(false), rowCount, validityBuf);
        return new MaskedArray(result, outputValidity);
    }

    private static Array emptyArray(DecodeContext ctx) {
        MemorySegment empty = ctx.arena().allocate(0);
        DType dt = ctx.dtype();
        PType ptype = ((DType.Primitive) dt).ptype();
        return toArray(dt, 0L, empty, ptype);
    }

    private static Array toArray(DType dtype, long n, MemorySegment seg, PType ptype) {
        return switch (ptype) {
            case I64, U64 -> new LongArray(dtype, n, seg);
            case I32, U32 -> new IntArray(dtype, n, seg);
            case I16, U16 -> new ShortArray(dtype, n, seg);
            case I8, U8 -> new ByteArray(dtype, n, seg);
            case F64 -> new DoubleArray(dtype, n, seg);
            case F32 -> new FloatArray(dtype, n, seg);
            case F16 -> new Float16Array(dtype, n, seg);
        };
    }

    private static long[] readLongs(MemorySegment buf, int count, PType ptype) {
        long[] out = new long[count];
        int elemSize = ptype.byteSize();
        long cap = SegmentBroadcast.capacity(buf, elemSize);
        for (int i = 0; i < count; i++) {
            long off = (i % cap) * elemSize;
            out[i] = switch (ptype) {
                case I8 -> buf.get(ValueLayout.JAVA_BYTE, off);
                case U8 -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, off));
                case I16 -> buf.get(PTypeIO.LE_SHORT, off);
                case U16, F16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, off));
                case I32 -> buf.get(PTypeIO.LE_INT, off);
                case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                case I64, U64 -> buf.get(PTypeIO.LE_LONG, off);
                case F32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                case F64 -> buf.get(PTypeIO.LE_LONG, off);
            };
        }
        return out;
    }

    private static int[] readIndices(MemorySegment buf, int count, PType indicesPtype) {
        int[] out = new int[count];
        int elemSize = indicesPtype.byteSize();
        long cap = SegmentBroadcast.capacity(buf, elemSize);
        switch (indicesPtype) {
            case U8 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Byte.toUnsignedInt(buf.get(ValueLayout.JAVA_BYTE, i % cap));
                }
            }
            case U16 -> {
                for (int i = 0; i < count; i++) {
                    out[i] = Short.toUnsignedInt(buf.get(PTypeIO.LE_SHORT, (i % cap) * 2));
                }
            }
            default ->
                    throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported indices ptype: " + indicesPtype);
        }
        return out;
    }

    private static long[] readUnsignedLongs(MemorySegment buf, int count, PType ptype) {
        long[] out = new long[count];
        int elemSize = ptype.byteSize();
        long cap = SegmentBroadcast.capacity(buf, elemSize);
        for (int i = 0; i < count; i++) {
            long off = (i % cap) * elemSize;
            out[i] = switch (ptype) {
                case U8 -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, off));
                case U16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, off));
                case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                case U64 -> buf.get(PTypeIO.LE_LONG, off);
                default ->
                        throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported offsets ptype: " + ptype);
            };
        }
        return out;
    }

    private static MemorySegment fromLongs(long[] decoded, int offset, int count, PType ptype, SegmentAllocator arena) {
        int elemSize = ptype.byteSize();
        MemorySegment seg = arena.allocate((long) count * elemSize);
        for (int i = 0; i < count; i++) {
            PTypeIO.set(seg, (long) i * elemSize, ptype, decoded[offset + i]);
        }
        return seg;
    }
}
