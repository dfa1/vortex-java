package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.RLEMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyRleByteArray;
import io.github.dfa1.vortex.reader.array.LazyRleIntArray;
import io.github.dfa1.vortex.reader.array.LazyRleLongArray;
import io.github.dfa1.vortex.reader.array.LazyRleShortArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for `fastlanes.rle`.
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

        int[] indices = readIndices(ArraySegments.of(indicesArr), (int) indicesLen, indicesPtype);
        long[] valuesIdxOffsets = readUnsignedLongs(
                ctx.decodeChildSegment(2, offsetsDtype, offsetsLen), (int) offsetsLen, offsetsPtype);
        long firstOffset = valuesLen > 0 && valuesIdxOffsets.length > 0 ? valuesIdxOffsets[0] : 0L;
        int numChunks = (int) (indicesLen / FL_CHUNK_SIZE);

        MemorySegment valuesSeg = ctx.decodeChildSegment(0, valuesDtype, valuesLen);
        Array result = switch (ptype) {
            case I64, U64 -> new LazyRleLongArray(ctx.dtype(), rowCount,
                    readLongs(valuesSeg, (int) valuesLen, ptype),
                    indices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset);
            case I32, U32 -> new LazyRleIntArray(ctx.dtype(), rowCount,
                    readInts(valuesSeg, (int) valuesLen, ptype),
                    indices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset);
            case I16, U16 -> new LazyRleShortArray(ctx.dtype(), rowCount,
                    readShorts(valuesSeg, (int) valuesLen, ptype),
                    indices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset,
                    ptype == PType.U16);
            case I8, U8 -> new LazyRleByteArray(ctx.dtype(), rowCount,
                    readBytes(valuesSeg, (int) valuesLen),
                    indices, valuesIdxOffsets, firstOffset, valuesLen, numChunks, offset,
                    ptype == PType.U8);
            default -> throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported ptype " + ptype);
        };

        if (indicesValidity == null) {
            return result;
        }
        int validityBytes = (int) ((rowCount + 7) / 8);
        MemorySegment validityBuf = ctx.arena().allocate(validityBytes);
        for (long j = 0; j < rowCount; j++) {
            if (indicesValidity.getBoolean(offset + j)) {
                int byteIdx = (int) (j >>> 3);
                byte current = validityBuf.get(ValueLayout.JAVA_BYTE, byteIdx);
                validityBuf.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) ((current & 0xff) | (1 << (j & 7))));
            }
        }
        BoolArray outputValidity = new MaterializedBoolArray(new DType.Bool(false), rowCount, validityBuf);
        return new MaskedArray(result, outputValidity);
    }

    private static Array emptyArray(DecodeContext ctx) {
        MemorySegment empty = ctx.arena().allocate(0);
        DType dt = ctx.dtype();
        PType ptype = ((DType.Primitive) dt).ptype();
        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(dt, 0L, empty);
            case I32, U32 -> new MaterializedIntArray(dt, 0L, empty);
            case I16, U16 -> new MaterializedShortArray(dt, 0L, empty);
            case I8, U8 -> new MaterializedByteArray(dt, 0L, empty);
            default -> throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported ptype " + ptype);
        };
    }

    private static long[] readLongs(MemorySegment buf, int count, PType ptype) {
        long[] out = new long[count];
        int elemSize = ptype.byteSize();
        long cap = SegmentBroadcast.capacity(buf, elemSize);
        for (int i = 0; i < count; i++) {
            long off = (i % cap) * elemSize;
            out[i] = switch (ptype) {
                case I64 -> buf.get(PTypeIO.LE_LONG, off);
                case U64 -> buf.get(PTypeIO.LE_LONG, off);
                default -> throw new VortexException(EncodingId.FASTLANES_RLE, "expected I64/U64, got " + ptype);
            };
        }
        return out;
    }

    private static int[] readInts(MemorySegment buf, int count, PType ptype) {
        int[] out = new int[count];
        int elemSize = ptype.byteSize();
        long cap = SegmentBroadcast.capacity(buf, elemSize);
        for (int i = 0; i < count; i++) {
            long off = (i % cap) * elemSize;
            out[i] = buf.get(PTypeIO.LE_INT, off);
        }
        return out;
    }

    private static short[] readShorts(MemorySegment buf, int count, PType ptype) {
        short[] out = new short[count];
        int elemSize = ptype.byteSize();
        long cap = SegmentBroadcast.capacity(buf, elemSize);
        for (int i = 0; i < count; i++) {
            long off = (i % cap) * elemSize;
            out[i] = buf.get(PTypeIO.LE_SHORT, off);
        }
        return out;
    }

    private static byte[] readBytes(MemorySegment buf, int count) {
        byte[] out = new byte[count];
        long cap = SegmentBroadcast.capacity(buf, 1);
        for (int i = 0; i < count; i++) {
            out[i] = buf.get(ValueLayout.JAVA_BYTE, i % cap);
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
}
