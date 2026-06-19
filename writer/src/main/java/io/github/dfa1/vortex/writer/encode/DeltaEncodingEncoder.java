package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.DeltaMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
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
        long[] longs = toLongs(data, ptype);
        int n = longs.length;
        int typeBits = typeBits(ptype);
        int lanes = lanes(ptype);
        long mask = typeMask(ptype);
        boolean unsign = isUnsigned(ptype);

        long minVal = 0L, maxVal = 0L;
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

        int numChunks = n == 0 ? 0 : (n + FL_CHUNK_SIZE - 1) / FL_CHUNK_SIZE;
        long paddedLen = (long) numChunks * FL_CHUNK_SIZE;
        int basesLen = numChunks * lanes;

        long[] basesAll = new long[basesLen];
        long[] deltasAll = new long[(int) paddedLen];
        long[] chunkBuf = new long[FL_CHUNK_SIZE];
        long[] transposed = new long[FL_CHUNK_SIZE];
        long[] chunkBases = new long[lanes];
        long[] chunkDelta = new long[FL_CHUNK_SIZE];

        for (int chunk = 0; chunk < numChunks; chunk++) {
            int start = chunk * FL_CHUNK_SIZE;
            int end = Math.min(start + FL_CHUNK_SIZE, n);
            for (int i = start; i < end; i++) {
                chunkBuf[i - start] = longs[i] & mask;
            }
            for (int i = end - start; i < FL_CHUNK_SIZE; i++) {
                chunkBuf[i] = 0L;
            }
            for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                transposed[i] = chunkBuf[transposeIndex(i)];
            }
            int basesOff = chunk * lanes;
            System.arraycopy(transposed, 0, basesAll, basesOff, lanes);
            System.arraycopy(basesAll, basesOff, chunkBases, 0, lanes);
            deltaChunk(transposed, chunkBases, lanes, typeBits, mask, chunkDelta);
            System.arraycopy(chunkDelta, 0, deltasAll, chunk * FL_CHUNK_SIZE, FL_CHUNK_SIZE);
        }

        MemorySegment basesSeg = fromLongs(basesAll, ptype, ctx.arena());
        MemorySegment deltasSeg = fromLongs(deltasAll, ptype, ctx.arena());

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
                int idx = iterateIndex(row, lane);
                long next = transposed[idx] & mask;
                out[idx] = (next - prev) & mask;
                prev = next;
            }
        }
    }

    private static long[] toLongs(Object data, PType ptype) {
        return switch (ptype) {
            case I8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = arr[i];
                }
                yield r;
            }
            case U8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Byte.toUnsignedLong(arr[i]);
                }
                yield r;
            }
            case I16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = arr[i];
                }
                yield r;
            }
            case U16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Short.toUnsignedLong(arr[i]);
                }
                yield r;
            }
            case I32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = arr[i];
                }
                yield r;
            }
            case U32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Integer.toUnsignedLong(arr[i]);
                }
                yield r;
            }
            case I64, U64 -> (long[]) data;
            default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
        };
    }

    private static boolean isUnsigned(PType ptype) {
        return switch (ptype) {
            case U8, U16, U32, U64 -> true;
            default -> false;
        };
    }

    private static byte[] statsBytes(PType ptype, long value) {
        if (isUnsigned(ptype)) {
            return ScalarValue.ofUint64Value(value).encode();
        }
        return ScalarValue.ofInt64Value(value).encode();
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

    private static MemorySegment fromLongs(long[] longs, PType ptype, SegmentAllocator arena) {
        if (ptype == PType.I64 || ptype == PType.U64) {
            MemorySegment dst = arena.allocate((long) longs.length * 8);
            MemorySegment.copy(MemorySegment.ofArray(longs), ValueLayout.JAVA_LONG, 0L, dst, PTypeIO.LE_LONG, 0L, longs.length);
            return dst;
        }
        int n = longs.length;
        long elemSize = ptype.byteSize();
        MemorySegment seg = arena.allocate(n * elemSize);
        for (int i = 0; i < n; i++) {
            PTypeIO.set(seg, i * elemSize, ptype, longs[i]);
        }
        return seg;
    }

}
