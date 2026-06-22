package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PrimitiveArrays;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.DeltaMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for `fastlanes.delta`.
public final class DeltaEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public DeltaEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_DELTA;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return switch (p.ptype()) {
            case I8, I16, I32, I64, U8, U16, U32, U64 -> true;
            default -> false;
        };
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        DeltaMetadata meta;
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            meta = new DeltaMetadata(0L, 0);
        } else {
            try {
                MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
                meta = DeltaMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.FASTLANES_DELTA, "invalid metadata", e);
            }
        }

        PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
        long rowCount = ctx.rowCount();
        int typeBits = typeBits(ptype);
        int lanes = lanes(ptype);
        long mask = typeMask(ptype);

        long deltasLen = meta.deltas_len();
        int offset = meta.offset();

        if (deltasLen == 0L) {
            MemorySegment empty = ctx.arena().allocate(0);
            return switch (ptype) {
                case I64, U64 -> new MaterializedLongArray(ctx.dtype(), 0L, empty);
                case I32, U32 -> new MaterializedIntArray(ctx.dtype(), 0L, empty);
                case I16, U16 -> new MaterializedShortArray(ctx.dtype(), 0L, empty);
                case I8, U8 -> new MaterializedByteArray(ctx.dtype(), 0L, empty);
                default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
            };
        }

        long basesLen = (deltasLen / FL_CHUNK_SIZE) * lanes;
        DType dtype = ctx.dtype();

        long[] basesAll = readLongs(ctx.decodeChildSegment(0, dtype, basesLen), (int) basesLen, ptype);
        long[] deltasAll = readLongs(ctx.decodeChildSegment(1, dtype, deltasLen), (int) deltasLen, ptype);

        int numChunks = (int) (deltasLen / FL_CHUNK_SIZE);
        long[] decoded = new long[(int) deltasLen];
        long[] untransposedChunk = new long[FL_CHUNK_SIZE];
        long[] chunkBases = new long[lanes];
        long[] chunkDeltas = new long[FL_CHUNK_SIZE];
        long[] chunkUndelta = new long[FL_CHUNK_SIZE];

        for (int chunk = 0; chunk < numChunks; chunk++) {
            int basesOff = chunk * lanes;
            int deltaOff = chunk * FL_CHUNK_SIZE;

            System.arraycopy(basesAll, basesOff, chunkBases, 0, lanes);
            System.arraycopy(deltasAll, deltaOff, chunkDeltas, 0, FL_CHUNK_SIZE);

            undeltaChunk(chunkDeltas, chunkBases, lanes, typeBits, mask, chunkUndelta);

            for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                untransposedChunk[transposeIndex(i)] = chunkUndelta[i];
            }
            System.arraycopy(untransposedChunk, 0, decoded, deltaOff, FL_CHUNK_SIZE);
        }

        long[] result = new long[(int) rowCount];
        System.arraycopy(decoded, offset, result, 0, (int) rowCount);

        MemorySegment seg = PrimitiveArrays.fromLongs(result, ptype, ctx.arena());
        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(ctx.dtype(), rowCount, seg);
            case I32, U32 -> new MaterializedIntArray(ctx.dtype(), rowCount, seg);
            case I16, U16 -> new MaterializedShortArray(ctx.dtype(), rowCount, seg);
            case I8, U8 -> new MaterializedByteArray(ctx.dtype(), rowCount, seg);
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        };
    }

    private static void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        for (int lane = 0; lane < lanes; lane++) {
            long prev = bases[lane] & mask;
            for (int row = 0; row < typeBits; row++) {
                int idx = iterateIndex(row, lane);
                long next = ((deltas[idx] & mask) + prev) & mask;
                out[idx] = next;
                prev = next;
            }
        }
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
                case U16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, off));
                case I32 -> buf.get(PTypeIO.LE_INT, off);
                case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                case I64, U64 -> buf.get(PTypeIO.LE_LONG, off);
                default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
            };
        }
        return out;
    }

    private static final int FL_CHUNK_SIZE = 1024;

    private static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    private static int transposeIndex(int idx) {
        int lane = idx % 16;
        int order = (idx / 16) % 8;
        int row = idx / 128;
        return lane * 64 + FL_ORDER[order] * 8 + row;
    }

    private static int iterateIndex(int row, int lane) {
        int o = row / 8;
        int s = row % 8;
        return FL_ORDER[o] * 16 + s * 128 + lane;
    }

    private static int lanes(PType ptype) {
        return FL_CHUNK_SIZE / (ptype.byteSize() * 8);
    }

    private static int typeBits(PType ptype) {
        return ptype.byteSize() * 8;
    }

    private static long typeMask(PType ptype) {
        int bits = ptype.byteSize() * 8;
        return bits == 64 ? -1L : (1L << bits) - 1;
    }

}
