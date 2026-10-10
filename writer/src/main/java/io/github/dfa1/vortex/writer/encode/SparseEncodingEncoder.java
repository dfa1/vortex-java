package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSparseMetadata;

import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.sparse`.
public final class SparseEncodingEncoder implements EncodingEncoder {

    /// This encoding, barred from its own children: the values child keeps the input dtype, so
    /// without this the cascade could sparse-encode the sparse values.
    private static final Set<EncodingId> SELF = Set.of(EncodingId.VORTEX_SPARSE);

    /// Candidates for compressing a boolean mask's patch-index array ([#encodeBool]) — a sorted,
    /// often-regular sequence (e.g. a periodic null pattern), so `vortex.sequence`/`fastlanes.delta`
    /// are tried first. Deliberately separate from [io.github.dfa1.vortex.writer.VortexWriter]'s
    /// general per-column cascade codec list, which excludes both: adding them there would change
    /// selection for every dense Primitive column, not just this narrow index-compression case.
    private static final List<EncodingEncoder> INDEX_CASCADE_CANDIDATES = List.of(
            new SequenceEncodingEncoder(),
            new DeltaEncodingEncoder(),
            new FrameOfReferenceEncodingEncoder(),
            new BitpackedEncodingEncoder(),
            new PrimitiveEncodingEncoder());

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SPARSE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public StatsOptions statsOptions() {
        return StatsOptions.defaults().withTrackMostFrequent(true);
    }

    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            return Estimate.COMPLETE;
        }
        // Rust's float sparse scheme only takes null-dominated arrays; nulls never reach a
        // primitive here (validity is split off by vortex.masked), so floats never qualify.
        if (p.ptype().isFloating()) {
            return Estimate.SKIP;
        }
        // Rust's SparseScheme (schemes/integer/sparse.rs): the most frequent value, whatever it
        // is, becomes the fill once it covers 90% of the array, and only the rest is stored. A
        // capped scan proves no value reaches half, let alone 90%.
        ArrayStats stats = data.stats();
        long n = stats.valueCount();
        long top = stats.topFrequency();
        if (n == 0 || stats.distinctCapped() || top == n || (double) top / n < 0.9) {
            return Estimate.SKIP;
        }
        return Estimate.ratio((double) n / (n - top));
    }

    /// Cascade gate: skip unless analytic sparse size beats raw-bitpacked size.
    /// Cascade variant: exposes patch-index and patch-value buffers as ChildSlot so the
    /// cascade can further compress them (bitpack on small-range U16/U32 indices, bitpack
    /// on small-range mantissa values). Matches Rust which stacks bitpack on Sparse's
    /// children and produces ~50% smaller dict-code segments on taxi tolls_amount /
    /// Airport_fee / congestion_surcharge.
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        // `data` is the whole logical column -- the fill value and patch positions are derived
        // from it below -- so the column's bounds are just its bounds, patches included.
        byte[][] stats = ZoneMapStats.of(dtype, data);
        if (!(dtype instanceof DType.Primitive p)) {
            return CascadeStep.notApplicable();
        }
        PType ptype = p.ptype();
        int n = arrayLength(data, ptype);
        if (n == 0) {
            return CascadeStep.notApplicable();
        }
        long fill = fillBits(ptype, data);
        int elemBytes = ptype.byteSize();
        PType idxPtype = PType.narrowestUnsigned(n);
        int idxBytes = idxPtype.byteSize();
        int patchCost = idxBytes + elemBytes;
        int maxPatches = (int) Math.min(Integer.MAX_VALUE, ((long) n * elemBytes / 2L) / patchCost);
        List<Integer> patchIdx = new ArrayList<>();
        List<Long> patchBits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long bits = readBits(data, ptype, i);
            if (bits != fill) {
                if (patchIdx.size() >= maxPatches) {
                    return CascadeStep.notApplicable();
                }
                patchIdx.add(i);
                patchBits.add(bits);
            }
        }
        int numPatches = patchIdx.size();

        // Owned buffers: fill scalar. idx and val moved to ChildSlot so cascade can bitpack.
        ProtoScalarValue fillScalar = scalar(ptype, fill);
        byte[] fillBytes = fillScalar.encode();
        MemorySegment fillBuf = ctx.arena().allocate(fillBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(fillBytes), 0, fillBuf, 0, fillBytes.length);

        Object idxArr = PrimitiveArrays.fromIntsArray(
                patchIdx.stream().mapToInt(Integer::intValue).toArray(), idxPtype, EncodingId.VORTEX_SPARSE);
        Object valArr = PrimitiveArrays.fromBitsArray(
                patchBits.stream().mapToLong(Long::longValue).toArray(), ptype, EncodingId.VORTEX_SPARSE);

        ProtoPatchesMetadata patchesMeta = new ProtoPatchesMetadata(
                numPatches,
                0L,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(idxPtype.ordinal()),
                null,
                null,
                null
        );
        byte[] metaBytes = new ProtoSparseMetadata(patchesMeta).encode();
        EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_SPARSE,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{null, null}, new int[]{0});
        DType idxDtype = new DType.Primitive(idxPtype, false);
        ChildSlot idxSlot = new ChildSlot(idxDtype, idxArr, 0, SELF);
        ChildSlot valSlot = new ChildSlot(dtype, valArr, 1, SELF);
        return new CascadeStep(partialRoot, List.of(EncodedBuffer.bytes(fillBuf)), List.of(idxSlot, valSlot), ZoneMapStats.minOf(stats), ZoneMapStats.maxOf(stats), true);
    }

    /// Encodes a boolean mask as `vortex.sparse`: fill = the majority value, patches = the minority
    /// positions. Unlike the numeric path (fill = most frequent value, `expectedRatio`-gated) this always
    /// builds the result — a two-valued domain has no "wrong" fill to guess, and the patch index
    /// array is run through the full [CascadingCompressor] so a clustered or regular null pattern
    /// (e.g. `fastlanes.delta` on a periodic run of nulls) can beat a raw 1-bit/row bitmap. Callers
    /// compare the returned size against a raw bitmap and keep whichever is smaller.
    ///
    /// @param validity per-row boolean array; must contain at least one `true` and one `false`
    ///                 (an all-same array is cheaper as `vortex.constant` — see [ConstantEncodingEncoder])
    /// @param ctx      encode context
    /// @return the encoded `vortex.sparse` result
    static EncodeResult encodeBool(boolean[] validity, EncodeContext ctx) {
        int n = validity.length;
        int trueCount = 0;
        for (boolean b : validity) {
            if (b) {
                trueCount++;
            }
        }
        boolean fillValue = trueCount * 2 >= n;
        int numPatches = fillValue ? n - trueCount : trueCount;
        int[] patchIdx = new int[numPatches];
        int p = 0;
        for (int i = 0; i < n; i++) {
            if (validity[i] != fillValue) {
                patchIdx[p++] = i;
            }
        }
        boolean[] patchVals = new boolean[numPatches];
        java.util.Arrays.fill(patchVals, !fillValue);

        PType idxPtype = PType.narrowestUnsigned(n);
        Object idxArr = PrimitiveArrays.fromIntsArray(patchIdx, idxPtype, EncodingId.VORTEX_SPARSE);

        ProtoScalarValue fillScalar = ProtoScalarValue.ofBoolValue(fillValue);
        byte[] fillBytes = fillScalar.encode();
        MemorySegment fillBuf = ctx.arena().allocate(fillBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(fillBytes), 0, fillBuf, 0, fillBytes.length);

        DType idxDtype = new DType.Primitive(idxPtype, false);
        EncodeResult idxResult = new CascadingCompressor(INDEX_CASCADE_CANDIDATES).encode(idxDtype, idxArr, ctx);
        EncodeResult valResult = new BoolEncodingEncoder().encode(DType.BOOL, patchVals, ctx);

        List<EncodedBuffer> buffers = new ArrayList<>();
        buffers.add(EncodedBuffer.bytes(fillBuf));
        buffers.addAll(idxResult.encodedBuffers());
        int valOffset = 1 + idxResult.buffers().size();
        buffers.addAll(valResult.encodedBuffers());

        EncodeNode idxNode = EncodeNode.remapBufferIndices(idxResult.rootNode(), 1);
        EncodeNode valNode = EncodeNode.remapBufferIndices(valResult.rootNode(), valOffset);

        ProtoPatchesMetadata patchesMeta = new ProtoPatchesMetadata(
                numPatches, 0L, io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(idxPtype.ordinal()),
                null, null, null);
        byte[] metaBytes = new ProtoSparseMetadata(patchesMeta).encode();

        EncodeNode root = new EncodeNode(EncodingId.VORTEX_SPARSE, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{idxNode, valNode}, new int[]{0});
        return new EncodeResult(root, List.copyOf(buffers), null, null);
    }
    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "encode only supports Primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        int n = arrayLength(data, ptype);
        long fill = n == 0 ? 0L : fillBits(ptype, data);

        List<Integer> patchIdx = new ArrayList<>();
        List<Long> patchBits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long bits = readBits(data, ptype, i);
            if (bits != fill) {
                patchIdx.add(i);
                patchBits.add(bits);
            }
        }

        int numPatches = patchIdx.size();
        PType idxPtype = PType.narrowestUnsigned(n);

        ProtoScalarValue fillScalar = scalar(ptype, fill);
        byte[] fillBytes = fillScalar.encode();
        MemorySegment fillBuf = ctx.arena().allocate(fillBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(fillBytes), 0, fillBuf, 0, fillBytes.length);

        MemorySegment idxBuf = buildIdxBuf(patchIdx, idxPtype, numPatches, ctx);
        MemorySegment valBuf = buildValBuf(patchBits, ptype, numPatches, ctx);

        ProtoPatchesMetadata patchesMeta = new ProtoPatchesMetadata(
                numPatches,
                0L,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(idxPtype.ordinal()),
                null,
                null,
                null
        );
        byte[] metaBytes = new ProtoSparseMetadata(patchesMeta).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_SPARSE, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{idxNode, valNode}, new int[]{0});
        return new EncodeResult(root, List.of(EncodedBuffer.bytes(fillBuf), EncodedBuffer.of(idxBuf, idxPtype),
                EncodedBuffer.of(valBuf, ptype)), null, null).withStats(ZoneMapStats.of(dtype, data));
    }

    private static int arrayLength(Object data, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F32 -> ((float[]) data).length;
            case F64 -> ((double[]) data).length;
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
        };
    }

    private static long readBits(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I8 -> ((byte[]) data)[i];
            case U8 -> Byte.toUnsignedLong(((byte[]) data)[i]);
            case I16 -> ((short[]) data)[i];
            case U16 -> Short.toUnsignedLong(((short[]) data)[i]);
            case I32 -> ((int[]) data)[i];
            case U32 -> Integer.toUnsignedLong(((int[]) data)[i]);
            case I64, U64 -> ((long[]) data)[i];
            case F32 -> Float.floatToRawIntBits(((float[]) data)[i]);
            case F64 -> Double.doubleToRawLongBits(((double[]) data)[i]);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
        };
    }

    /// The fill: the most frequent value's bits, as Rust's sparse compress takes it from the
    /// stats' `most_frequent_value`, widened the way [#readBits] widens.
    private static long fillBits(PType ptype, Object data) {
        return ArrayStats.compute(ptype, data, StatsOptions.defaults().withTrackMostFrequent(true)).mostFrequentBits();
    }

    private static ProtoScalarValue scalar(PType ptype, long bits) {
        return switch (ptype) {
            case I8, I16, I32, I64 -> ProtoScalarValue.ofInt64Value(bits);
            case U8, U16, U32, U64 -> ProtoScalarValue.ofUint64Value(bits);
            case F32 -> ProtoScalarValue.ofF32Value(Float.intBitsToFloat((int) bits));
            case F64 -> ProtoScalarValue.ofF64Value(Double.longBitsToDouble(bits));
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
        };
    }

    private static MemorySegment buildIdxBuf(List<Integer> patchIdx, PType idxPtype, int numPatches, EncodeContext ctx) {
        int elemBytes = idxPtype.byteSize();
        MemorySegment seg = ctx.arena().allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, idxPtype, patchIdx.get(i));
        }
        return seg;
    }

    private static MemorySegment buildValBuf(List<Long> patchBits, PType ptype, int numPatches, EncodeContext ctx) {
        int elemBytes = ptype.byteSize();
        MemorySegment seg = ctx.arena().allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, ptype, patchBits.get(i));
        }
        return seg;
    }
}
