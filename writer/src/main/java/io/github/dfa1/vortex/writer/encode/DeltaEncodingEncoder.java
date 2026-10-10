package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.proto.ProtoDeltaMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;

/// Write-only encoder for `fastlanes.delta`.
public final class DeltaEncodingEncoder implements EncodingEncoder {

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
        Deltas d = deltas(PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_DELTA), ptype);
        MemorySegment basesSeg = PrimitiveArrays.fromLongs(d.bases(), ptype, ctx.arena());
        MemorySegment deltasSeg = PrimitiveArrays.fromLongs(d.deltas(), ptype, ctx.arena());
        EncodeNode basesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode deltasNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode root = new EncodeNode(EncodingId.FASTLANES_DELTA, MemorySegment.ofArray(d.metadata()),
                new EncodeNode[]{basesNode, deltasNode}, new int[0]);
        return new EncodeResult(root, List.of(EncodedBuffer.of(basesSeg, ptype), EncodedBuffer.of(deltasSeg, ptype)),
                d.statsMin(), d.statsMax());
    }

    /// Barred from both children (issue #410, Rust's `DeltaScheme` descendant exclusions): delta
    /// encoding data that is already delta encoded never pays off.
    private static final Set<EncodingId> CHILDREN_EXCLUDED = Set.of(EncodingId.FASTLANES_DELTA);

    /// Below one FastLanes chunk the transpose has nothing to work with (Rust's `MIN_DELTA_LEN`).
    private static final int MIN_DELTA_LEN = FastLanes.CHUNK;

    /// Cascading delta, mirroring Rust's `DeltaScheme`: the bases and deltas become open children
    /// (Rust ids bases=0, deltas=1, also our wire order) that the compressor can FoR / bit-pack —
    /// delta alone keeps the byte width. Same layout and metadata as [#encode].
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_DELTA);
        if (longs.length < MIN_DELTA_LEN) {
            return CascadeStep.notApplicable();
        }
        Deltas d = deltas(longs, ptype);
        EncodeNode partialRoot = new EncodeNode(EncodingId.FASTLANES_DELTA, MemorySegment.ofArray(d.metadata()),
                new EncodeNode[]{null, null}, new int[0]);
        DType childDtype = dtype.withNullable(false);
        List<ChildSlot> slots = List.of(
                new ChildSlot(childDtype, PrimitiveArrays.fromLongsArray(d.bases(), ptype, EncodingId.FASTLANES_DELTA),
                        0, CHILDREN_EXCLUDED),
                new ChildSlot(childDtype, PrimitiveArrays.fromLongsArray(d.deltas(), ptype, EncodingId.FASTLANES_DELTA),
                        1, CHILDREN_EXCLUDED));
        return new CascadeStep(partialRoot, List.of(), slots, d.statsMin(), d.statsMax(), true);
    }

    /// A column delta encoded in transposed FastLanes chunks.
    ///
    /// @param bases     per chunk, one base per lane
    /// @param deltas    per padded row, the wrapping difference from the previous row in its lane
    /// @param paddedLen row count rounded up to a whole chunk
    /// @param statsMin  zone-map minimum, `null` when empty
    /// @param statsMax  zone-map maximum, `null` when empty
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Deltas(long[] bases, long[] deltas, long paddedLen, byte[] statsMin, byte[] statsMax) {

        byte[] metadata() {
            return new ProtoDeltaMetadata(paddedLen, 0).encode();
        }
    }

    private static Deltas deltas(long[] longs, PType ptype) {
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
            SimdOperationsSupport.preferred().deltaChunk(transposed, chunkBases, lanes, typeBits, mask, chunkDelta);
            System.arraycopy(chunkDelta, 0, deltasAll, chunk * FastLanes.CHUNK, FastLanes.CHUNK);
        }

        byte[] statsMin = n > 0 ? statsBytes(ptype, minVal) : null;
        byte[] statsMax = n > 0 ? statsBytes(ptype, maxVal) : null;
        return new Deltas(basesAll, deltasAll, paddedLen, statsMin, statsMax);
    }

    private static byte[] statsBytes(PType ptype, long value) {
        if (ptype.isUnsigned()) {
            return ProtoScalarValue.ofUint64Value(value).encode();
        }
        return ProtoScalarValue.ofInt64Value(value).encode();
    }

}
