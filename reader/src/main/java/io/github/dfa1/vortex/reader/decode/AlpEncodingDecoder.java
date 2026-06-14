package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.AlpDoubleArray;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.FusedAlpForBitpackedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ALPMetadata;
import io.github.dfa1.vortex.proto.BitPackedMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code vortex.alp}.
public final class AlpEncodingDecoder implements EncodingDecoder {
    private static final double[] F10_F64 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22, 1e23};
    private static final double[] IF10_F64 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9, 1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18, 1e-19, 1e-20, 1e-21, 1e-22, 1e-23};
    private static final float[] F10_F32 = {1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f};
    private static final float[] IF10_F32 = {1e-0f, 1e-1f, 1e-2f, 1e-3f, 1e-4f, 1e-5f, 1e-6f, 1e-7f, 1e-8f, 1e-9f, 1e-10f};
    private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
    private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public AlpEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ALP;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return p.ptype() == PType.F64 || p.ptype() == PType.F32;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        ALPMetadata meta;
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            meta = new ALPMetadata(0, 0, null);
        } else {
            try {
                MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
                meta = ALPMetadata.decode(metaSeg, 0, metaSeg.byteSize());
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

        return switch (ptype) {
            case F64 -> decodeF64(ctx, meta, expE, expF, n);
            case F32 -> decodeF32(ctx, meta, expE, expF, n);
            default -> throw new VortexException(EncodingId.VORTEX_ALP, "unsupported dtype " + ptype);
        };
    }

    private static Array decodeF64(DecodeContext ctx, ALPMetadata meta, int expE, int expF, long n) {
        double scale = F10_F64[expF] * IF10_F64[expE];

        if (meta.patches() == null) {
            // Fused path: chain is ALP(FoR(Bitpacked)) and Bitpacked has no
            // patches. Hold the raw packed buffer + chain params so
            // sumWhereGt skips the FoR add and ALP buffer entirely.
            FusedAlpForBitpackedDoubleArray fused = tryDetectFusedChain(ctx, n, scale);
            if (fused != null) {
                return fused;
            }
        }

        MemorySegment src = ctx.decodeChildSegment(0, I64_DTYPE, n);

        // Lazy path: full-size writable source, no patches. Fallback for
        // chains the fused detector did not match.
        if (meta.patches() == null
                && !src.isReadOnly()
                && SegmentBroadcast.capacity(src, 8) == n) {
            return new AlpDoubleArray(ctx.dtype(), n, src, scale);
        }

        // Fallback: materialise into a buffer. Required for read-only mmap
        // sources, broadcast (ConstantEncoding) sources, and patched chunks.
        MemorySegment buf = src.isReadOnly() ? ctx.arena().allocate(n * 8, 8) : src;
        if (src.isReadOnly()) {
            long srcCap = SegmentBroadcast.capacity(src, 8);
            if (srcCap == n) {
                for (long i = 0; i < n; i++) {
                    buf.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) src.getAtIndex(PTypeIO.LE_LONG, i) * scale);
                }
            } else {
                for (long i = 0; i < n; i++) {
                    buf.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) src.getAtIndex(PTypeIO.LE_LONG, i % srcCap) * scale);
                }
            }
        } else {
            for (long i = 0; i < n; i++) {
                buf.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) buf.getAtIndex(PTypeIO.LE_LONG, i) * scale);
            }
        }

        if (meta.patches() != null) {
            applyPatches(ctx, meta.patches(), buf, 8);
        }

        return new MaterializedDoubleArray(ctx.dtype(), n, buf.asReadOnly());
    }

    private static Array decodeF32(DecodeContext ctx, ALPMetadata meta, int expE, int expF, long n) {
        float df = F10_F32[expF];
        float de = IF10_F32[expE];

        MemorySegment src32 = ctx.decodeChildSegment(0, I32_DTYPE, n);
        MemorySegment buf32 = src32.isReadOnly() ? ctx.arena().allocate(n * 4, 4) : src32;
        if (src32.isReadOnly()) {
            long srcCap = SegmentBroadcast.capacity(src32, 4);
            if (srcCap == n) {
                for (long i = 0; i < n; i++) {
                    buf32.setAtIndex(PTypeIO.LE_FLOAT, i, (float) src32.getAtIndex(PTypeIO.LE_INT, i) * df * de);
                }
            } else {
                for (long i = 0; i < n; i++) {
                    buf32.setAtIndex(PTypeIO.LE_FLOAT, i, (float) src32.getAtIndex(PTypeIO.LE_INT, i % srcCap) * df * de);
                }
            }
        } else {
            for (long i = 0; i < n; i++) {
                buf32.setAtIndex(PTypeIO.LE_FLOAT, i, (float) buf32.getAtIndex(PTypeIO.LE_INT, i) * df * de);
            }
        }

        if (meta.patches() != null) {
            applyPatches(ctx, meta.patches(), buf32, 4);
        }

        return new FloatArray(ctx.dtype(), n, buf32.asReadOnly());
    }

    /// Detects the ALP(FoR(Bitpacked)) chain on the encode tree and, when
    /// matched, returns a [FusedAlpForBitpackedDoubleArray] that holds the
    /// raw bitpacked buffer plus the chain parameters. Returns {@code null}
    /// when the chain does not match or required metadata is unavailable.
    private static FusedAlpForBitpackedDoubleArray tryDetectFusedChain(DecodeContext ctx, long n, double scale) {
        ArrayNode[] alpChildren = ctx.node().children();
        if (alpChildren.length == 0 || !(alpChildren[0] instanceof KnownArrayNode forNode)
                || forNode.encodingId() != EncodingId.FASTLANES_FOR) {
            return null;
        }
        if (forNode.children().length == 0 || !(forNode.children()[0] instanceof KnownArrayNode bpNode)
                || bpNode.encodingId() != EncodingId.FASTLANES_BITPACKED) {
            return null;
        }

        ByteBuffer forMeta = forNode.metadata();
        if (forMeta == null || !forMeta.hasRemaining()) {
            return null;
        }
        long ref;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(forMeta.duplicate());
            ScalarValue scalar = ScalarValue.decode(metaSeg, 0, metaSeg.byteSize());
            ref = scalar.int64_value() != null ? scalar.int64_value()
                    : scalar.uint64_value() != null ? scalar.uint64_value()
                    : 0L;
        } catch (IOException e) {
            return null;
        }

        ByteBuffer bpMeta = bpNode.metadata();
        BitPackedMetadata bp;
        if (bpMeta == null || !bpMeta.hasRemaining()) {
            bp = new BitPackedMetadata(0, 0, null);
        } else {
            try {
                MemorySegment metaSeg = MemorySegment.ofBuffer(bpMeta.duplicate());
                bp = BitPackedMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            } catch (IOException e) {
                return null;
            }
        }
        if (bp.bit_width() == 0 || bp.patches() != null) {
            return null;
        }
        if (bpNode.bufferIndices().length == 0) {
            return null;
        }
        MemorySegment packed = ctx.segmentBuffers()[bpNode.bufferIndices()[0]];
        return new FusedAlpForBitpackedDoubleArray(
                ctx.dtype(), n, packed, bp.bit_width(), bp.offset(), ref, scale);
    }

    private static void applyPatches(DecodeContext ctx, PatchesMetadata pm, MemorySegment out, int elemBytes) {
        long numPatches = pm.len();
        long offset = pm.offset();
        PType idxPtype = PType.fromOrdinal(pm.indices_ptype().value());
        int idxBytes = idxPtype.byteSize();

        MemorySegment idxSeg = ctx.decodeChildSegment(1, new DType.Primitive(idxPtype, false), numPatches);
        MemorySegment valSeg = ctx.decodeChildSegment(2, ctx.dtype(), numPatches);

        long idxCap = SegmentBroadcast.capacity(idxSeg, idxBytes);
        long valCap = SegmentBroadcast.capacity(valSeg, elemBytes);
        if (idxCap >= numPatches && valCap >= numPatches) {
            for (long i = 0; i < numPatches; i++) {
                long absIdx = readUnsigned(idxSeg, i * idxBytes, idxPtype) - offset;
                MemorySegment.copy(valSeg, i * elemBytes, out, absIdx * elemBytes, elemBytes);
            }
        } else {
            for (long i = 0; i < numPatches; i++) {
                long absIdx = readUnsigned(idxSeg, (i % idxCap) * idxBytes, idxPtype) - offset;
                MemorySegment.copy(valSeg, (i % valCap) * elemBytes, out, absIdx * elemBytes, elemBytes);
            }
        }
    }

    private static long readUnsigned(MemorySegment seg, long off, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
            case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
            case U64 -> seg.get(PTypeIO.LE_LONG, off);
            default -> throw new VortexException(EncodingId.VORTEX_ALP, "non-unsigned patch index ptype " + ptype);
        };
    }
}
