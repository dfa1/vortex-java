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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
        Runs runs = runs(longs);
        boolean[] valuesArr = new boolean[runs.valuesCount()];
        for (int i = 0; i < valuesArr.length; i++) {
            valuesArr[i] = runs.values()[i] != 0L;
        }
        EncodeResult valuesResult = new BoolEncodingEncoder().encode(DType.BOOL, valuesArr, ctx);
        MemorySegment indicesSeg = toIndicesSeg(runs.indices(), runs.paddedLen(), ctx.arena());
        MemorySegment offsetsSeg = fromLongsU64(runs.offsets(), runs.numChunks(), ctx.arena());

        PType indicesPtype = PType.U16;
        PType offsetsPtype = PType.U64;
        byte[] metaBytes = runs.metadata();

        int indicesBufIdx = valuesResult.buffers().size();
        EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, indicesBufIdx);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, indicesBufIdx + 1);

        List<EncodedBuffer> buffers = new ArrayList<>(valuesResult.encodedBuffers());
        buffers.add(EncodedBuffer.of(indicesSeg, indicesPtype));
        buffers.add(EncodedBuffer.of(offsetsSeg, offsetsPtype));

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
        // Run-length encoding preserves the value set, so the runs bound the column exactly as the
        // raw values do.
        byte[][] stats = ZoneMapStats.of(dtype, data);

        Runs runs = runs(longs);
        MemorySegment valuesSeg = fromLongs(runs.values(), runs.valuesCount(), ptype, ctx.arena());
        MemorySegment indicesSeg = toIndicesSeg(runs.indices(), runs.paddedLen(), ctx.arena());
        MemorySegment offsetsSeg = fromLongsU64(runs.offsets(), runs.numChunks(), ctx.arena());

        PType indicesPtype = PType.U16;
        PType offsetsPtype = PType.U64;
        byte[] metaBytes = runs.metadata();

        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(
                EncodingId.FASTLANES_RLE,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{valuesNode, indicesNode, offsetsNode},
                new int[0]);
        return new EncodeResult(root, List.of(EncodedBuffer.of(valuesSeg, ptype), EncodedBuffer.of(indicesSeg, indicesPtype),
                EncodedBuffer.of(offsetsSeg, offsetsPtype)), null, null).withStats(stats);
    }

    /// Barred from the indices and offsets children (issue #410, Rust's `rle_descendant_exclusions`):
    /// Dict and Sparse cannot pay off on per-chunk run positions or monotone offsets. Rust keeps a
    /// RunEnd rule there commented out as unsound, so RunEnd is not barred. RLE names itself, as
    /// Rust's compressor never repeats a scheme within one chain.
    private static final Set<EncodingId> POSITIONS_EXCLUDED =
            Set.of(EncodingId.FASTLANES_RLE, EncodingId.VORTEX_DICT, EncodingId.VORTEX_SPARSE);

    /// Barred from the values child: only RLE itself (no rule upstream).
    private static final Set<EncodingId> VALUES_EXCLUDED = Set.of(EncodingId.FASTLANES_RLE);

    /// Cascading RLE, mirroring Rust's `IntRLEScheme`: the values, indices and offsets become open
    /// children (Rust ids values=0, indices=1, offsets=2, also our wire order) for the compressor
    /// to bit-pack / FoR, instead of raw buffers. Same layout and metadata as [#encode].
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        long[] longs = toLongs(data, ptype);
        if (longs.length == 0) {
            return CascadeStep.notApplicable();
        }
        Runs runs = runs(longs);
        EncodeNode partialRoot = new EncodeNode(EncodingId.FASTLANES_RLE, MemorySegment.ofArray(runs.metadata()),
                new EncodeNode[]{null, null, null}, new int[0]);
        long[] values = Arrays.copyOf(runs.values(), runs.valuesCount());
        List<ChildSlot> slots = List.of(
                new ChildSlot(dtype, PrimitiveArrays.fromLongsArray(values, ptype, EncodingId.FASTLANES_RLE), 0,
                        VALUES_EXCLUDED),
                new ChildSlot(new DType.Primitive(PType.U16, false), runs.indices(), 1, POSITIONS_EXCLUDED),
                new ChildSlot(new DType.Primitive(PType.U64, false), runs.offsets(), 2, POSITIONS_EXCLUDED));
        byte[][] stats = ZoneMapStats.of(dtype, data);
        return new CascadeStep(partialRoot, List.of(), slots, ZoneMapStats.minOf(stats), ZoneMapStats.maxOf(stats), true);
    }

    /// One column run-length encoded in FastLanes 1024-row chunks.
    ///
    /// @param values      the run values, chunk after chunk (first `valuesCount` are used)
    /// @param valuesCount number of run values
    /// @param indices     per padded row, the index of its run within its chunk
    /// @param paddedLen   row count rounded up to a whole chunk
    /// @param offsets     per chunk, the index of its first run value
    /// @param numChunks   chunk count
    private record Runs(long[] values, int valuesCount, short[] indices, int paddedLen, long[] offsets,
            int numChunks) {

        byte[] metadata() {
            return new ProtoRLEMetadata(
                    valuesCount,
                    paddedLen,
                    io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U16.ordinal()),
                    numChunks,
                    io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U64.ordinal()),
                    0L
            ).encode();
        }
    }

    /// Run-length encodes `longs` chunk by chunk; the last chunk is padded with its final value.
    private static Runs runs(long[] longs) {
        int n = longs.length;
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
        return new Runs(globalValues, globalValuesCount, globalIndices, paddedLen, valuesIdxOffsets, numChunks);
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
        // The values ptype is unknown here; 8 bytes covers every primitive, and an empty buffer is never
        // sliced, so declaring more than the element needs cannot trip the Rust reader.
        return new EncodeResult(root, List.of(new EncodedBuffer(empty, Long.BYTES), EncodedBuffer.of(empty, indicesPtype),
                EncodedBuffer.of(empty, offsetsPtype)), null, null);
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
