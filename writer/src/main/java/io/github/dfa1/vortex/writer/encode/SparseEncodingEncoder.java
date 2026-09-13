package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSparseMetadata;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.sparse`.
public final class SparseEncodingEncoder implements EncodingEncoder {

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
        return new StatsOptions(false, true);
    }

    @Override
    public Estimate expectedRatio(DType dtype, Object data, ArrayStats stats) {
        if (!(dtype instanceof DType.Primitive) || !stats.hasMostFrequent()) {
            return Estimate.COMPLETE;
        }
        long n = stats.valueCount();
        // Sparse stores fill scalar (hardcoded 0) + n - topFreq patches. Skip unless the
        // dominant value's bit pattern is zero AND it covers more than half the array —
        // otherwise the patch buffer dwarfs raw storage.
        if (n == 0 || stats.mostFrequentBits() != 0L || stats.topFrequency() * 2 < n) {
            return Estimate.SKIP;
        }
        return Estimate.COMPLETE;
    }

    /// Cascade gate: skip unless analytic sparse size beats raw-bitpacked size.
    /// Cascade variant: exposes patch-index and patch-value buffers as ChildSlot so the
    /// cascade can further compress them (bitpack on small-range U16/U32 indices, bitpack
    /// on small-range mantissa values). Matches Rust which stacks bitpack on Sparse's
    /// children and produces ~50% smaller dict-code segments on taxi tolls_amount /
    /// Airport_fee / congestion_surcharge.
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            return CascadeStep.notApplicable();
        }
        PType ptype = p.ptype();
        int n = arrayLength(data, ptype);
        if (n == 0) {
            return CascadeStep.notApplicable();
        }
        int elemBytes = ptype.byteSize();
        PType idxPtype = PType.narrowestUnsigned(n);
        int idxBytes = idxPtype.byteSize();
        int patchCost = idxBytes + elemBytes;
        int maxPatches = (int) Math.min(Integer.MAX_VALUE, ((long) n * elemBytes / 2L) / patchCost);
        List<Integer> patchIdx = new ArrayList<>();
        List<Long> patchBits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long bits = readBits(data, ptype, i);
            if (bits != 0L) {
                if (patchIdx.size() >= maxPatches) {
                    return CascadeStep.notApplicable();
                }
                patchIdx.add(i);
                patchBits.add(bits);
            }
        }
        int numPatches = patchIdx.size();

        // Owned buffers: fill scalar. idx and val moved to ChildSlot so cascade can bitpack.
        ProtoScalarValue fillScalar = zeroScalar(ptype);
        byte[] fillBytes = fillScalar.encode();
        MemorySegment fillBuf = ctx.arena().allocate(fillBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(fillBytes), 0, fillBuf, 0, fillBytes.length);

        Object idxArr = idxArr(patchIdx, idxPtype);
        Object valArr = valArr(patchBits, ptype);

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
        ChildSlot idxSlot = new ChildSlot(idxDtype, idxArr, 0);
        ChildSlot valSlot = new ChildSlot(dtype, valArr, 1);
        return new CascadeStep(partialRoot, List.of(fillBuf), List.of(idxSlot, valSlot), null, null, true);
    }

    private static Object idxArr(List<Integer> patchIdx, PType idxPtype) {
        int n = patchIdx.size();
        return switch (idxPtype) {
            case U8 -> {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = patchIdx.get(i).byteValue();
                }
                yield a;
            }
            case U16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = patchIdx.get(i).shortValue();
                }
                yield a;
            }
            default -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = patchIdx.get(i);
                }
                yield a;
            }
        };
    }

    private static Object valArr(List<Long> patchBits, PType ptype) {
        int n = patchBits.size();
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = patchBits.get(i).byteValue();
                }
                yield a;
            }
            case I16, U16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = patchBits.get(i).shortValue();
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = patchBits.get(i).intValue();
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = patchBits.get(i);
                }
                yield a;
            }
            case F32 -> {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = Float.intBitsToFloat(patchBits.get(i).intValue());
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[n];
                for (int i = 0; i < n; i++) {
                    a[i] = Double.longBitsToDouble(patchBits.get(i));
                }
                yield a;
            }
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
        };
    }

    /// Encodes a boolean mask as `vortex.sparse`: fill = the majority value, patches = the minority
    /// positions. Unlike the numeric path (hardcoded fill = 0, `expectedRatio`-gated) this always
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
        Object idxArr = idxArr(patchIdx, idxPtype);

        ProtoScalarValue fillScalar = ProtoScalarValue.ofBoolValue(fillValue);
        byte[] fillBytes = fillScalar.encode();
        MemorySegment fillBuf = ctx.arena().allocate(fillBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(fillBytes), 0, fillBuf, 0, fillBytes.length);

        DType idxDtype = new DType.Primitive(idxPtype, false);
        EncodeResult idxResult = new CascadingCompressor(INDEX_CASCADE_CANDIDATES).encode(idxDtype, idxArr, ctx);
        EncodeResult valResult = new BoolEncodingEncoder().encode(DType.BOOL, patchVals, ctx);

        List<MemorySegment> buffers = new ArrayList<>();
        buffers.add(fillBuf);
        buffers.addAll(idxResult.buffers());
        int valOffset = 1 + idxResult.buffers().size();
        buffers.addAll(valResult.buffers());

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

    private static Object idxArr(int[] patchIdx, PType idxPtype) {
        int n = patchIdx.length;
        return switch (idxPtype) {
            case U8 -> {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (byte) patchIdx[i];
                }
                yield a;
            }
            case U16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (short) patchIdx[i];
                }
                yield a;
            }
            default -> patchIdx;
        };
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "encode only supports Primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        int n = arrayLength(data, ptype);

        List<Integer> patchIdx = new ArrayList<>();
        List<Long> patchBits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long bits = readBits(data, ptype, i);
            if (bits != 0L) {
                patchIdx.add(i);
                patchBits.add(bits);
            }
        }

        int numPatches = patchIdx.size();
        PType idxPtype = PType.narrowestUnsigned(n);

        ProtoScalarValue fillScalar = zeroScalar(ptype);
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
        return new EncodeResult(root, List.of(fillBuf, idxBuf, valBuf), null, null);
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

    private static ProtoScalarValue zeroScalar(PType ptype) {
        return switch (ptype) {
            case I8, I16, I32, I64 -> ProtoScalarValue.ofInt64Value(0L);
            case U8, U16, U32, U64 -> ProtoScalarValue.ofUint64Value(0L);
            case F32 -> ProtoScalarValue.ofF32Value(0.0f);
            case F64 -> ProtoScalarValue.ofF64Value(0.0);
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
