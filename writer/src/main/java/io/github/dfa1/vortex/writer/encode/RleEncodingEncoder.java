package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoRLEMetadata;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `fastlanes.rle`.
public final class RleEncodingEncoder implements EncodingEncoder {

    private static final int FL_CHUNK_SIZE = 1024;

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_RLE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && !p.ptype().isFloating();
    }

    /// Encodes a boolean array as `fastlanes.rle`: the same FastLanes 1024-row chunked
    /// layout as the numeric path (see [#encode]), with a `vortex.bool`-encoded values pool
    /// (at most 2 distinct values per chunk for a two-valued domain) instead of a ptype-width
    /// primitive one. Complements [RunEndEncodingEncoder#encodeBool] (plain run-ends); callers
    /// compare against alternatives and keep whichever is smallest.
    ///
    /// @param validity per-row boolean array; must contain at least one `true` and one `false`
    /// @param ctx      encode context
    /// @return the encoded `fastlanes.rle` result
    static EncodeResult encodeBool(boolean[] validity, EncodeContext ctx) {
        int n = validity.length;
        long[] longs = new long[n];
        for (int i = 0; i < n; i++) {
            longs[i] = validity[i] ? 1L : 0L;
        }

        int numChunks = (n + FL_CHUNK_SIZE - 1) / FL_CHUNK_SIZE;
        int paddedLen = numChunks * FL_CHUNK_SIZE;

        long[] globalValues = new long[paddedLen];
        short[] globalIndices = new short[paddedLen];
        long[] valuesIdxOffsets = new long[numChunks];

        long[] chunkInput = new long[FL_CHUNK_SIZE];
        long[] chunkValues = new long[FL_CHUNK_SIZE];
        short[] chunkIndices = new short[FL_CHUNK_SIZE];

        int globalValuesCount = 0;
        for (int chunk = 0; chunk < numChunks; chunk++) {
            int chunkStart = chunk * FL_CHUNK_SIZE;
            int chunkEnd = Math.min(chunkStart + FL_CHUNK_SIZE, n);
            int chunkLen = chunkEnd - chunkStart;

            System.arraycopy(longs, chunkStart, chunkInput, 0, chunkLen);
            long lastVal = longs[chunkEnd - 1];
            for (int i = chunkLen; i < FL_CHUNK_SIZE; i++) {
                chunkInput[i] = lastVal;
            }

            int numChunkValues = rleEncode(chunkInput, chunkValues, chunkIndices);

            valuesIdxOffsets[chunk] = globalValuesCount;
            System.arraycopy(chunkValues, 0, globalValues, globalValuesCount, numChunkValues);
            globalValuesCount += numChunkValues;

            System.arraycopy(chunkIndices, 0, globalIndices, chunkStart, FL_CHUNK_SIZE);
        }

        boolean[] valuesArr = new boolean[globalValuesCount];
        for (int i = 0; i < globalValuesCount; i++) {
            valuesArr[i] = globalValues[i] != 0L;
        }
        EncodeResult valuesResult = new BoolEncodingEncoder().encode(DType.BOOL, valuesArr, ctx);
        MemorySegment indicesSeg = toIndicesSeg(globalIndices, paddedLen, ctx.arena());
        MemorySegment offsetsSeg = fromLongsU64(valuesIdxOffsets, numChunks, ctx.arena());

        PType indicesPtype = PType.U16;
        PType offsetsPtype = PType.U64;

        byte[] metaBytes = new ProtoRLEMetadata(
                globalValuesCount,
                paddedLen,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(indicesPtype.ordinal()),
                numChunks,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(offsetsPtype.ordinal()),
                0L
        ).encode();

        int indicesBufIdx = valuesResult.buffers().size();
        EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, indicesBufIdx);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, indicesBufIdx + 1);

        List<MemorySegment> buffers = new ArrayList<>(valuesResult.buffers());
        buffers.add(indicesSeg);
        buffers.add(offsetsSeg);

        EncodeNode root = new EncodeNode(
                EncodingId.FASTLANES_RLE,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{valuesResult.rootNode(), indicesNode, offsetsNode},
                new int[0]);
        return new EncodeResult(root, List.copyOf(buffers), null, null);
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_RLE, "encode only supports Primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        long[] longs = toLongs(data, ptype);
        int n = longs.length;

        if (n == 0) {
            return encodeEmpty(ctx);
        }

        int numChunks = (n + FL_CHUNK_SIZE - 1) / FL_CHUNK_SIZE;
        int paddedLen = numChunks * FL_CHUNK_SIZE;

        long[] globalValues = new long[paddedLen];
        short[] globalIndices = new short[paddedLen];
        long[] valuesIdxOffsets = new long[numChunks];

        long[] chunkInput = new long[FL_CHUNK_SIZE];
        long[] chunkValues = new long[FL_CHUNK_SIZE];
        short[] chunkIndices = new short[FL_CHUNK_SIZE];

        int globalValuesCount = 0;

        for (int chunk = 0; chunk < numChunks; chunk++) {
            int chunkStart = chunk * FL_CHUNK_SIZE;
            int chunkEnd = Math.min(chunkStart + FL_CHUNK_SIZE, n);
            int chunkLen = chunkEnd - chunkStart;

            System.arraycopy(longs, chunkStart, chunkInput, 0, chunkLen);
            long lastVal = longs[chunkEnd - 1];
            for (int i = chunkLen; i < FL_CHUNK_SIZE; i++) {
                chunkInput[i] = lastVal;
            }

            int numChunkValues = rleEncode(chunkInput, chunkValues, chunkIndices);

            valuesIdxOffsets[chunk] = globalValuesCount;
            System.arraycopy(chunkValues, 0, globalValues, globalValuesCount, numChunkValues);
            globalValuesCount += numChunkValues;

            System.arraycopy(chunkIndices, 0, globalIndices, chunkStart, FL_CHUNK_SIZE);
        }

        MemorySegment valuesSeg = fromLongs(globalValues, globalValuesCount, ptype, ctx.arena());
        MemorySegment indicesSeg = toIndicesSeg(globalIndices, paddedLen, ctx.arena());
        MemorySegment offsetsSeg = fromLongsU64(valuesIdxOffsets, numChunks, ctx.arena());

        PType indicesPtype = PType.U16;
        PType offsetsPtype = PType.U64;

        byte[] metaBytes = new ProtoRLEMetadata(
                globalValuesCount,
                paddedLen,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(indicesPtype.ordinal()),
                numChunks,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(offsetsPtype.ordinal()),
                0L
        ).encode();

        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(
                EncodingId.FASTLANES_RLE,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{valuesNode, indicesNode, offsetsNode},
                new int[0]);
        return new EncodeResult(root, List.of(valuesSeg, indicesSeg, offsetsSeg), null, null);
    }

    private static int rleEncode(long[] input, long[] chunkValues, short[] chunkIndices) {
        short posVal = 0;
        int valIdx = 1;
        long prev = input[0];
        chunkValues[0] = prev;
        chunkIndices[0] = 0;

        for (int i = 1; i < FL_CHUNK_SIZE; i++) {
            long cur = input[i];
            if (cur != prev) {
                chunkValues[valIdx] = cur;
                valIdx++;
                posVal++;
                prev = cur;
            }
            chunkIndices[i] = posVal;
        }
        return valIdx;
    }

    private static EncodeResult encodeEmpty(EncodeContext ctx) {
        MemorySegment empty = ctx.arena().allocate(0);
        PType indicesPtype = PType.U16;
        PType offsetsPtype = PType.U64;
        byte[] metaBytes = new ProtoRLEMetadata(
                0L,
                0L,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(indicesPtype.ordinal()),
                0L,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(offsetsPtype.ordinal()),
                0L
        ).encode();
        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(
                EncodingId.FASTLANES_RLE,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{valuesNode, indicesNode, offsetsNode},
                new int[0]);
        return new EncodeResult(root, List.of(empty, empty, empty), null, null);
    }

    private static long[] toLongs(Object data, PType ptype) {
        return switch (ptype) {
            case F32 -> {
                float[] arr = (float[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Float.floatToRawIntBits(arr[i]);
                }
                yield r;
            }
            case F64 -> {
                double[] arr = (double[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Double.doubleToRawLongBits(arr[i]);
                }
                yield r;
            }
            case F16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    r[i] = Short.toUnsignedLong(arr[i]);
                }
                yield r;
            }
            // Integer ptypes share the standard widen; floats above keep RLE's raw-bit packing.
            default -> PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_RLE);
        };
    }

    private static MemorySegment fromLongs(long[] values, int count, PType ptype, SegmentAllocator arena) {
        int elemSize = ptype.byteSize();
        MemorySegment seg = arena.allocate((long) count * elemSize);
        for (int i = 0; i < count; i++) {
            PTypeIO.set(seg, (long) i * elemSize, ptype, values[i]);
        }
        return seg;
    }

    private static MemorySegment fromLongsU64(long[] values, int count, SegmentAllocator arena) {
        MemorySegment seg = arena.allocate((long) count * 8);
        for (int i = 0; i < count; i++) {
            seg.setAtIndex(VortexFormat.LE_LONG, i, values[i]);
        }
        return seg;
    }

    private static MemorySegment toIndicesSeg(short[] indices, int count, SegmentAllocator arena) {
        MemorySegment seg = arena.allocate((long) count * 2);
        for (int i = 0; i < count; i++) {
            seg.setAtIndex(VortexFormat.LE_SHORT, i, indices[i]);
        }
        return seg;
    }
}
