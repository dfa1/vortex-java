package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoALPMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyAlpDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyAlpFloatArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyConstantFloatArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.alp`.
public final class AlpEncodingDecoder implements EncodingDecoder {
    private static final double[] F10_F64 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22, 1e23};
    private static final double[] IF10_F64 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9, 1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18, 1e-19, 1e-20, 1e-21, 1e-22, 1e-23};
    private static final float[] F10_F32 = {1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f};
    private static final float[] IF10_F32 = {1e-0f, 1e-1f, 1e-2f, 1e-3f, 1e-4f, 1e-5f, 1e-6f, 1e-7f, 1e-8f, 1e-9f, 1e-10f};

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ALP;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        ProtoALPMetadata meta;
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            meta = new ProtoALPMetadata(0, 0, null);
        } else {
            try {
                meta = ProtoALPMetadata.decode(rawMeta, 0, rawMeta.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_ALP, "invalid metadata", e);
            }
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_ALP, "expected primitive dtype, got " + ctx.dtype());
        }

        int expE = meta.exp_e();
        int expF = meta.exp_f();
        PType ptype = p.ptype();
        long n = ctx.rowCount();

        // Validity mirrors the Rust reference (`ValidityChild<ALP>`): an ALP array's
        // validity IS its encoded child's validity, and the encoded child's dtype
        // inherits the ALP dtype's nullability. Decode the child as an Array so a
        // nullable primitive surfaces its MaskedArray instead of being flattened to a
        // raw segment (which silently dropped nulls — #210).
        DType.Primitive encodedDtype = new DType.Primitive(
                ptype == PType.F64 ? PType.I64 : PType.I32, p.nullable());
        Array encoded = ctx.decodeChild(0, encodedDtype, n);
        BoolArray validity = null;
        Array rawEncoded = encoded;
        if (encoded instanceof MaskedArray masked) {
            rawEncoded = masked.inner();
            validity = masked.validity();
        }
        MemorySegment src = ctx.materialize(rawEncoded);

        Array result = switch (ptype) {
            case F64 -> decodeF64(ctx, meta, expE, expF, n, src);
            case F32 -> decodeF32(ctx, meta, expE, expF, n, src);
            default -> throw new VortexException(EncodingId.VORTEX_ALP, "unsupported dtype " + ptype);
        };
        return validity != null ? new MaskedArray(result, validity) : result;
    }

    private static Array decodeF64(DecodeContext ctx, ProtoALPMetadata meta, int expE, int expF, long n,
            MemorySegment src) {
        checkExponents(expE, expF, F10_F64.length);
        // Decode formula mirrors the Rust reference (`ALPFloat::decode_single`): two-step
        // `encoded * F10[f] * IF10[e]`. A pre-multiplied `scale = F10[f] * IF10[e]`
        // gives different IEEE rounding for non-trivial `expF`, breaking round-trip with
        // the encoder's verify step.
        double df = F10_F64[expF];
        double de = IF10_F64[expE];
        long srcCap = SegmentBroadcast.capacity(src, 8);
        checkSource(srcCap, n);

        if (meta.patches() == null) {
            if (srcCap >= n) {
                return new LazyAlpDoubleArray(ctx.dtype(), n, src, df, de);
            }
            // broadcast without patches: decode single encoded value → constant
            double v = src.getAtIndex(VortexFormat.LE_LONG, 0) * df * de;
            return new LazyConstantDoubleArray(ctx.dtype(), n, v);
        }

        // patches path: materialize to scatter-write exceptions
        MemorySegment buf = ctx.arena().allocate(n * 8, 8);
        if (srcCap == n) {
            for (long i = 0; i < n; i++) {
                buf.setAtIndex(VortexFormat.LE_DOUBLE, i, src.getAtIndex(VortexFormat.LE_LONG, i) * df * de);
            }
        } else {
            for (long i = 0; i < n; i++) {
                buf.setAtIndex(VortexFormat.LE_DOUBLE, i, src.getAtIndex(VortexFormat.LE_LONG, i % srcCap) * df * de);
            }
        }
        applyPatches(ctx, meta.patches(), buf, 8);
        return new MaterializedDoubleArray(ctx.dtype(), n, buf.asReadOnly());
    }

    private static Array decodeF32(DecodeContext ctx, ProtoALPMetadata meta, int expE, int expF, long n,
            MemorySegment src) {
        checkExponents(expE, expF, F10_F32.length);
        float df = F10_F32[expF];
        float de = IF10_F32[expE];
        long srcCap = SegmentBroadcast.capacity(src, 4);
        checkSource(srcCap, n);

        if (meta.patches() == null) {
            if (srcCap >= n) {
                return new LazyAlpFloatArray(ctx.dtype(), n, src, df, de);
            }
            // broadcast without patches: decode single encoded value → constant
            float v = src.getAtIndex(VortexFormat.LE_INT, 0) * df * de;
            return new LazyConstantFloatArray(ctx.dtype(), n, v);
        }

        // patches path: materialize to scatter-write exceptions
        MemorySegment buf = ctx.arena().allocate(n * 4, 4);
        if (srcCap == n) {
            for (long i = 0; i < n; i++) {
                buf.setAtIndex(VortexFormat.LE_FLOAT, i, src.getAtIndex(VortexFormat.LE_INT, i) * df * de);
            }
        } else {
            for (long i = 0; i < n; i++) {
                buf.setAtIndex(VortexFormat.LE_FLOAT, i, src.getAtIndex(VortexFormat.LE_INT, i % srcCap) * df * de);
            }
        }
        applyPatches(ctx, meta.patches(), buf, 4);
        return new MaterializedFloatArray(ctx.dtype(), n, buf.asReadOnly());
    }

    /// Rejects out-of-range ALP exponents before they index the power-of-ten tables.
    ///
    /// `exp_e` and `exp_f` are untrusted metadata read verbatim from the file; a negative or
    /// oversized exponent used to escape as an `ArrayIndexOutOfBoundsException` from the
    /// table lookup rather than a [VortexException] (ADR 0003). The check is O(1) and runs
    /// once per decode, outside every loop.
    ///
    /// @param expE   the `e` exponent, an index into the inverse power-of-ten table
    /// @param expF   the `f` exponent, an index into the power-of-ten table
    /// @param maxExp exclusive upper bound — the table length for this float width
    private static void checkExponents(int expE, int expF, int maxExp) {
        if (expE < 0 || expE >= maxExp || expF < 0 || expF >= maxExp) {
            throw new VortexException(EncodingId.VORTEX_ALP,
                    "exponents (e=" + expE + ", f=" + expF + ") out of range [0," + maxExp + ")");
        }
    }

    /// Rejects an encoded child that carries no element at all.
    ///
    /// Every path below reads at least element 0 — the broadcast path reads exactly it, and
    /// the row-wise paths wrap with `% srcCap` — so a zero-length child would either read
    /// off the segment or divide by zero. Checked once, outside the row loops.
    ///
    /// @param srcCap number of elements physically present in the encoded child
    /// @param n      logical row count
    private static void checkSource(long srcCap, long n) {
        if (srcCap == 0 && n > 0) {
            throw new VortexException(EncodingId.VORTEX_ALP,
                    "empty encoded child for " + n + " rows");
        }
    }

    private static void applyPatches(DecodeContext ctx, ProtoPatchesMetadata pm, MemorySegment out, int elemBytes) {
        long numPatches = pm.len();
        if (numPatches == 0) {
            return;
        }
        long offset = pm.offset();
        PType idxPtype = PType.fromOrdinal(pm.indices_ptype().value());
        int idxBytes = idxPtype.byteSize();
        long n = out.byteSize() / elemBytes;

        MemorySegment idxSeg = ctx.decodeChildSegment(1, new DType.Primitive(idxPtype, false), numPatches);
        MemorySegment valSeg = ctx.decodeChildSegment(2, ctx.dtype(), numPatches);

        long idxCap = SegmentBroadcast.capacity(idxSeg, idxBytes);
        long valCap = SegmentBroadcast.capacity(valSeg, elemBytes);
        if (idxCap == 0 || valCap == 0) {
            throw new VortexException(EncodingId.VORTEX_ALP,
                    "empty patch child for " + numPatches + " declared patches (indices="
                            + idxSeg.byteSize() + " bytes, values=" + valSeg.byteSize() + " bytes)");
        }
        if (idxCap >= numPatches && valCap >= numPatches) {
            for (long i = 0; i < numPatches; i++) {
                long absIdx = readUnsigned(idxSeg, i * idxBytes, idxPtype) - offset;
                checkPatchIndex(absIdx, n);
                MemorySegment.copy(valSeg, i * elemBytes, out, absIdx * elemBytes, elemBytes);
            }
        } else {
            for (long i = 0; i < numPatches; i++) {
                long absIdx = readUnsigned(idxSeg, (i % idxCap) * idxBytes, idxPtype) - offset;
                checkPatchIndex(absIdx, n);
                MemorySegment.copy(valSeg, (i % valCap) * elemBytes, out, absIdx * elemBytes, elemBytes);
            }
        }
    }

    /// Guards a patch scatter-write against an untrusted index, mirroring the identical
    /// check in [BitpackedEncodingDecoder]: a patch index outside the row range (or one
    /// pushed negative by `patches.offset`) must fail as a [VortexException] rather than as
    /// a raw `IndexOutOfBoundsException` from the copy. Patches are sparse, so the per-patch
    /// test costs nothing on the row-wise decode loops.
    ///
    /// @param absIdx absolute row index of the patch, after subtracting `patches.offset`
    /// @param n      logical row count
    private static void checkPatchIndex(long absIdx, long n) {
        if (absIdx < 0 || absIdx >= n) {
            throw new VortexException(EncodingId.VORTEX_ALP,
                    "patch index " + absIdx + " out of range [0," + n + ")");
        }
    }

    private static long readUnsigned(MemorySegment seg, long off, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case U16 -> Short.toUnsignedLong(seg.get(VortexFormat.LE_SHORT, off));
            case U32 -> Integer.toUnsignedLong(seg.get(VortexFormat.LE_INT, off));
            case U64 -> seg.get(VortexFormat.LE_LONG, off);
            default -> throw new VortexException(EncodingId.VORTEX_ALP, "non-unsigned patch index ptype " + ptype);
        };
    }
}
