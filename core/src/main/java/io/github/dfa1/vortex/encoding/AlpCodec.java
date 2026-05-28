package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.alp} — Adaptive Lossless floating-Point compression.
///
/// <p>Metadata: protobuf {@code ALPMetadata} — {@code exp_e u32} (tag 1),
/// {@code exp_f u32} (tag 2), optional {@code patches PatchesMetadata} (tag 3).
///
/// <p>Child slot 0: encoded integers (I32 for F32, I64 for F64 parent), rowCount = array len.
/// Child slot 1: patch indices (if patches), rowCount = patches.len.
/// Child slot 2: patch values  (if patches, same dtype as parent), rowCount = patches.len.
/// Child slot 3: chunk offsets (optional, ignored — patches applied by absolute index).
///
/// <p>Decode: {@code decoded[i] = (float/double) encoded[i] * F10[exp_f] * IF10[exp_e]},
/// then overwrite {@code decoded[indices[j] - offset] = values[j]} for each patch.
public final class AlpCodec implements Decoder {

    // Powers of 10 for F64 (index 0..18 used by the encoder).
    private static final double[] F10_F64 = {
        1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,  1e8,  1e9,
        1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19,
        1e20, 1e21, 1e22, 1e23
    };

    private static final double[] IF10_F64 = {
        1e-0,  1e-1,  1e-2,  1e-3,  1e-4,  1e-5,  1e-6,  1e-7,  1e-8,  1e-9,
        1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18, 1e-19,
        1e-20, 1e-21, 1e-22, 1e-23
    };

    // Powers of 10 for F32.
    private static final float[] F10_F32 = {
        1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f
    };

    private static final float[] IF10_F32 = {
        1e-0f, 1e-1f, 1e-2f, 1e-3f, 1e-4f, 1e-5f, 1e-6f, 1e-7f, 1e-8f, 1e-9f, 1e-10f
    };

    @Override
    public String encodingId() {
        return "vortex.alp";
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new IllegalStateException("vortex.alp: missing metadata");
        }
        byte[] metaBytes = new byte[rawMeta.remaining()];
        rawMeta.duplicate().get(metaBytes);

        EncodingProtos.ALPMetadata meta;
        try {
            meta = EncodingProtos.ALPMetadata.parseFrom(metaBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("vortex.alp: invalid metadata", e);
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new IllegalStateException("vortex.alp: expected primitive dtype, got " + ctx.dtype());
        }

        int  expE    = meta.getExpE();
        int  expF    = meta.getExpF();
        PType ptype  = p.ptype();
        long n       = ctx.rowCount();

        return switch (ptype) {
            case F64 -> decodeF64(ctx, meta, expE, expF, n);
            case F32 -> decodeF32(ctx, meta, expE, expF, n);
            default  -> throw new IllegalStateException("vortex.alp: unsupported dtype " + ptype);
        };
    }

    // ── F64 ───────────────────────────────────────────────────────────────────

    private Array decodeF64(DecodeContext ctx, EncodingProtos.ALPMetadata meta, int expE, int expF, long n) {
        DType encodedDtype = new DType.Primitive(PType.I64, false);
        Array encoded = decodeChildAs(ctx, 0, encodedDtype, n);

        // Precompute single factor — avoids 2 FP mults per element in the hot loop.
        double factor = F10_F64[expF] * IF10_F64[expE];

        byte[] outBytes = new byte[Math.toIntExact(n * 8)];
        MemorySegment out = MemorySegment.ofArray(outBytes);
        var srcLayout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var dstLayout = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        MemorySegment src = encoded.buffer(0);
        // Strength-reduce: running byte offset instead of i*8 per iteration.
        for (long off = 0, end = n * 8; off < end; off += 8) {
            long   encVal = src.get(srcLayout, off);
            double dec    = (double) encVal * factor;
            out.set(dstLayout, off, dec);
        }

        if (meta.hasPatches()) {
            applyPatchesF64(ctx, meta.getPatches(), out, n);
        }

        return new Array(ctx.dtype(), n, new MemorySegment[]{out}, new Array[0], ArrayStats.empty());
    }

    private void applyPatchesF64(DecodeContext ctx, EncodingProtos.PatchesMetadata pm,
                                  MemorySegment out, long n) {
        long numPatches  = pm.getLen();
        long offset      = pm.getOffset();
        PType idxPtype   = ptypeFromProto(pm.getIndicesPtype());

        DType idxDtype = new DType.Primitive(idxPtype, false);
        DType valDtype = ctx.dtype();

        Array idxArr = decodeChildAs(ctx, 1, idxDtype, numPatches);
        Array valArr = decodeChildAs(ctx, 2, valDtype, numPatches);

        var valLayout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var dstLayout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        MemorySegment idxSeg = idxArr.buffer(0);
        MemorySegment valSeg = valArr.buffer(0);

        for (long i = 0; i < numPatches; i++) {
            long absIdx  = readUnsigned(idxSeg, i, idxPtype) - offset;
            long rawBits = valSeg.get(valLayout, i * 8);
            out.set(dstLayout, absIdx * 8, rawBits);
        }
    }

    // ── F32 ───────────────────────────────────────────────────────────────────

    private Array decodeF32(DecodeContext ctx, EncodingProtos.ALPMetadata meta, int expE, int expF, long n) {
        DType encodedDtype = new DType.Primitive(PType.I32, false);
        Array encoded = decodeChildAs(ctx, 0, encodedDtype, n);

        float factor = F10_F32[expF] * IF10_F32[expE];

        byte[] outBytes = new byte[Math.toIntExact(n * 4)];
        MemorySegment out = MemorySegment.ofArray(outBytes);
        var srcLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var dstLayout = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        MemorySegment src = encoded.buffer(0);
        for (long off = 0, end = n * 4; off < end; off += 4) {
            int   encVal = src.get(srcLayout, off);
            float dec    = (float) encVal * factor;
            out.set(dstLayout, off, dec);
        }

        if (meta.hasPatches()) {
            applyPatchesF32(ctx, meta.getPatches(), out, n);
        }

        return new Array(ctx.dtype(), n, new MemorySegment[]{out}, new Array[0], ArrayStats.empty());
    }

    private void applyPatchesF32(DecodeContext ctx, EncodingProtos.PatchesMetadata pm,
                                  MemorySegment out, long n) {
        long numPatches = pm.getLen();
        long offset     = pm.getOffset();
        PType idxPtype  = ptypeFromProto(pm.getIndicesPtype());

        DType idxDtype = new DType.Primitive(idxPtype, false);
        DType valDtype = ctx.dtype();

        Array idxArr = decodeChildAs(ctx, 1, idxDtype, numPatches);
        Array valArr = decodeChildAs(ctx, 2, valDtype, numPatches);

        var valLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var dstLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        MemorySegment idxSeg = idxArr.buffer(0);
        MemorySegment valSeg = valArr.buffer(0);

        for (long i = 0; i < numPatches; i++) {
            long absIdx  = readUnsigned(idxSeg, i, idxPtype) - offset;
            int rawBits  = valSeg.get(valLayout, i * 4);
            out.set(dstLayout, absIdx * 4, rawBits);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
        ArrayNode childNode = parent.node().children()[childIdx];
        DecodeContext childCtx = new DecodeContext(
            childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
        return parent.registry().decode(childCtx);
    }

    private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case U8  -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(
                seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 2));
            case U32 -> Integer.toUnsignedLong(
                seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4));
            case U64 -> seg.get(
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 8);
            default  -> throw new IllegalStateException("vortex.alp: non-unsigned patch index ptype " + ptype);
        };
    }

    private static PType ptypeFromProto(DTypeProtos.PType proto) {
        return PType.values()[proto.getNumber()];
    }
}
