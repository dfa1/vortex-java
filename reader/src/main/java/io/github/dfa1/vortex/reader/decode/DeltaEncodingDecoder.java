package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoDeltaMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `fastlanes.delta`.
public final class DeltaEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_DELTA;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        ProtoDeltaMetadata meta;
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            meta = new ProtoDeltaMetadata(0L, 0);
        } else {
            try {
                MemorySegment metaSeg = rawMeta;
                meta = ProtoDeltaMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.FASTLANES_DELTA, "invalid metadata", e);
            }
        }

        PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
        long rowCount = ctx.rowCount();
        int typeBits = ptype.bits();
        int lanes = FastLanes.lanes(ptype);
        long mask = FastLanes.lowMask(ptype.bits());

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

        long basesLen = (deltasLen / FastLanes.CHUNK) * lanes;
        DType dtype = ctx.dtype();

        long[] basesAll = readLongs(ctx.decodeChildSegment(0, dtype, basesLen), (int) basesLen, ptype);
        long[] deltasAll = readLongs(ctx.decodeChildSegment(1, dtype, deltasLen), (int) deltasLen, ptype);

        int numChunks = (int) (deltasLen / FastLanes.CHUNK);
        long[] decoded = new long[(int) deltasLen];
        long[] untransposedChunk = new long[FastLanes.CHUNK];
        long[] chunkBases = new long[lanes];
        long[] chunkDeltas = new long[FastLanes.CHUNK];
        long[] chunkUndelta = new long[FastLanes.CHUNK];

        for (int chunk = 0; chunk < numChunks; chunk++) {
            int basesOff = chunk * lanes;
            int deltaOff = chunk * FastLanes.CHUNK;

            System.arraycopy(basesAll, basesOff, chunkBases, 0, lanes);
            System.arraycopy(deltasAll, deltaOff, chunkDeltas, 0, FastLanes.CHUNK);

            undeltaChunk(chunkDeltas, chunkBases, lanes, typeBits, mask, chunkUndelta);

            for (int i = 0; i < FastLanes.CHUNK; i++) {
                untransposedChunk[FastLanes.transposeIndex(i)] = chunkUndelta[i];
            }
            System.arraycopy(untransposedChunk, 0, decoded, deltaOff, FastLanes.CHUNK);
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
                int idx = FastLanes.iterateIndex(row, lane);
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
                case I16 -> buf.get(VortexFormat.LE_SHORT, off);
                case U16 -> Short.toUnsignedLong(buf.get(VortexFormat.LE_SHORT, off));
                case I32 -> buf.get(VortexFormat.LE_INT, off);
                case U32 -> Integer.toUnsignedLong(buf.get(VortexFormat.LE_INT, off));
                case I64, U64 -> buf.get(VortexFormat.LE_LONG, off);
                default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
            };
        }
        return out;
    }

}
