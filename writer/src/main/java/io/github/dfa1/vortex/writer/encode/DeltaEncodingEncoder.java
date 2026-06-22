package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.FastLanes;
import io.github.dfa1.vortex.encoding.PrimitiveArrays;
import io.github.dfa1.vortex.proto.DeltaMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/// Write-only encoder for `fastlanes.delta`.
public final class DeltaEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public DeltaEncodingEncoder() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_DELTA);
        int n = longs.length;
        int typeBits = ptype.bits();
        int lanes = FastLanes.lanes(ptype);
        long mask = FastLanes.lowMask(ptype.bits());
        boolean unsign = ptype.isUnsigned();

        long minVal = 0L;
        long maxVal = 0L;
        if (n > 0) {
            minVal = longs[0];
            maxVal = longs[0];
            for (int i = 1; i < n; i++) {
                long v = longs[i];
                if (unsign ? Long.compareUnsigned(v, minVal) < 0 : v < minVal) {
                    minVal = v;
                }
                if (unsign ? Long.compareUnsigned(v, maxVal) > 0 : v > maxVal) {
                    maxVal = v;
                }
            }
        }

        int numChunks = n == 0 ? 0 : (n + FastLanes.CHUNK - 1) / FastLanes.CHUNK;
        long paddedLen = (long) numChunks * FastLanes.CHUNK;
        int basesLen = numChunks * lanes;

        long[] basesAll = new long[basesLen];
        long[] deltasAll = new long[(int) paddedLen];
        long[] chunkBuf = new long[FastLanes.CHUNK];
        long[] transposed = new long[FastLanes.CHUNK];
        long[] chunkBases = new long[lanes];
        long[] chunkDelta = new long[FastLanes.CHUNK];

        for (int chunk = 0; chunk < numChunks; chunk++) {
            int start = chunk * FastLanes.CHUNK;
            int end = Math.min(start + FastLanes.CHUNK, n);
            for (int i = start; i < end; i++) {
                chunkBuf[i - start] = longs[i] & mask;
            }
            for (int i = end - start; i < FastLanes.CHUNK; i++) {
                chunkBuf[i] = 0L;
            }
            for (int i = 0; i < FastLanes.CHUNK; i++) {
                transposed[i] = chunkBuf[FastLanes.transposeIndex(i)];
            }
            int basesOff = chunk * lanes;
            System.arraycopy(transposed, 0, basesAll, basesOff, lanes);
            System.arraycopy(basesAll, basesOff, chunkBases, 0, lanes);
            deltaChunk(transposed, chunkBases, lanes, typeBits, mask, chunkDelta);
            System.arraycopy(chunkDelta, 0, deltasAll, chunk * FastLanes.CHUNK, FastLanes.CHUNK);
        }

        MemorySegment basesSeg = PrimitiveArrays.fromLongs(basesAll, ptype, ctx.arena());
        MemorySegment deltasSeg = PrimitiveArrays.fromLongs(deltasAll, ptype, ctx.arena());

        byte[] metaBytes = new DeltaMetadata(paddedLen, 0).encode();

        byte[] statsMin = n > 0 ? statsBytes(ptype, minVal) : null;
        byte[] statsMax = n > 0 ? statsBytes(ptype, maxVal) : null;

        EncodeNode basesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode deltasNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode root = new EncodeNode(EncodingId.FASTLANES_DELTA, ByteBuffer.wrap(metaBytes),
                new EncodeNode[]{basesNode, deltasNode}, new int[0]);
        return new EncodeResult(root, List.of(basesSeg, deltasSeg), statsMin, statsMax);
    }

    private static void deltaChunk(long[] transposed, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        for (int lane = 0; lane < lanes; lane++) {
            long prev = bases[lane] & mask;
            for (int row = 0; row < typeBits; row++) {
                int idx = FastLanes.iterateIndex(row, lane);
                long next = transposed[idx] & mask;
                out[idx] = (next - prev) & mask;
                prev = next;
            }
        }
    }

    private static byte[] statsBytes(PType ptype, long value) {
        if (ptype.isUnsigned()) {
            return ScalarValue.ofUint64Value(value).encode();
        }
        return ScalarValue.ofInt64Value(value).encode();
    }

}
