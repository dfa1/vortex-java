package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// Write-only encoder for `fastlanes.for` (Frame of Reference).
public final class FrameOfReferenceEncodingEncoder implements EncodingEncoder {

    /// Creates the encoder.
    public FrameOfReferenceEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_FOR;
    }

    /// Rust's `FoRScheme` estimate (`vortex-btrblocks` `schemes/integer/for_.rs`), from min/max
    /// alone: skip when there is nothing to subtract (min 0), nothing left (constant), or when
    /// plain bit-packing would be as narrow; else the type width over the offset width.
    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        if (ctx.finishedCascading()) {
            return Estimate.SKIP;
        }
        PType ptype = ((DType.Primitive) dtype).ptype();
        ArrayStats stats = data.stats();
        long span = stats.maxMinusMin();
        if (stats.min() == 0 || span == 0) {
            return Estimate.SKIP;
        }
        int forBitWidth = 64 - Long.numberOfLeadingZeros(span);
        // Only when min >= 0: otherwise bit-packing cannot apply without ZigZag
        int maxLog = stats.maxIlog2(ptype);
        if (!stats.minIsNegative(ptype) && maxLog >= 0 && forBitWidth >= maxLog + 1) {
            return Estimate.SKIP;
        }
        return Estimate.ratio((double) ptype.bits() / forBitWidth);
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && p.ptype().isInteger();
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_FOR);
        int n = longs.length;

        long ref = computeRef(longs, n, ptype);
        MemorySegment residuals = toResidualBuffer(longs, ref, ptype, ctx);
        MemorySegment meta = buildForMeta(ref, ptype);

        EncodeNode child = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode root = new EncodeNode(EncodingId.FASTLANES_FOR, meta, new EncodeNode[]{child}, new int[0]);
        MinMax stats = PrimitiveEncodingEncoder.minMaxStats(ptype, data);
        return new EncodeResult(root, List.of(EncodedBuffer.of(residuals, ptype)),
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats));
    }

    /// A nullable array passes its validity down to the residuals, as Rust's `FoR::encode` does.
    @Override
    public boolean acceptsNullable(DType dtype) {
        return accepts(dtype);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + dtype);
        }
        if (data instanceof NullableData nullable) {
            return encodeNullableCascade(p, nullable, encodeCtx);
        }
        PType ptype = p.ptype();
        long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_FOR);
        int n = longs.length;

        long ref = computeRef(longs, n, ptype);
        // Skip when ref == 0 and ptype is unsigned: residuals == input, so FOR adds metadata
        // overhead (ref scalar + extra node) for zero compression benefit over plain bitpack.
        // Matches Rust IntFoRScheme's skip estimate for this case.
        if (ref == 0L && ptype.isUnsigned()) {
            return CascadeStep.notApplicable();
        }
        return step(p, ref, residualsAsNativeArray(longs, ref, ptype), PrimitiveEncodingEncoder.minMaxStats(ptype, data),
                encodeCtx);
    }

    /// Rust's v1 `FoRScheme::compress`: the residuals are bit-packed at once, never cascaded
    /// ("if any other scheme was preferable, it would be chosen instead of bitpacking",
    /// `schemes/integer/for_.rs`), and stay canonical when bit-packing cannot narrow them.
    private static CascadeStep step(DType.Primitive dtype, long ref, Object residuals, MinMax stats,
                                    EncodeContext ctx) {
        EncodeResult child = ctx.excluded().contains(EncodingId.FASTLANES_BITPACKED)
                ? new PrimitiveEncodingEncoder().encode(dtype, residuals, ctx)
                : BitpackedEncodingEncoder.encodeLeaf(dtype, residuals, ctx);
        EncodeNode root = new EncodeNode(EncodingId.FASTLANES_FOR, buildForMeta(ref, dtype.ptype()),
                new EncodeNode[]{child.rootNode()}, new int[0]);
        return CascadeStep.terminal(new EncodeResult(root, child.encodedBuffers(),
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats)));
    }

    /// Rust's `FoR::encode` on a nullable array: the reference is the smallest valid value, and a
    /// null slot's residual is zero, so it costs no bits in the bit-packing below
    /// (`for_compress.rs`). The residuals keep the validity.
    private static CascadeStep encodeNullableCascade(DType.Primitive dtype, NullableData data, EncodeContext ctx) {
        PType ptype = dtype.ptype();
        long[] valid = PrimitiveArrays.toLongs(data.validValues(), ptype, EncodingId.FASTLANES_FOR);
        long ref = computeRef(valid, valid.length, ptype);
        if (ref == 0L && ptype.isUnsigned()) {
            return CascadeStep.notApplicable();
        }
        long[] longs = PrimitiveArrays.toLongs(data.values(), ptype, EncodingId.FASTLANES_FOR).clone();
        boolean[] validity = data.validity();
        for (int i = 0; i < longs.length; i++) {
            if (!validity[i]) {
                longs[i] = ref;
            }
        }
        return step(dtype, ref, new NullableData(residualsAsNativeArray(longs, ref, ptype), validity),
                ZoneMapStats.of(dtype.withNullable(false), data), ctx);
    }

    // Rust takes the reference from Stat::Min, an unsigned min for unsigned ptypes: a signed one
    // picks a u64 above 2^63 over a smaller value below it, so a column straddling 2^63 got full-width
    // residuals. Widened U8..U32 values are zero-extended, so the U64 order serves every unsigned width.
    private static long computeRef(long[] longs, int n, PType ptype) {
        return n > 0 ? SimdOperationsSupport.preferred().minMax(longs, ptype.isUnsigned() ? PType.U64 : PType.I64)[0] : 0L;
    }

    private static MemorySegment buildForMeta(long ref, PType ptype) {
        boolean unsigned = switch (ptype) {
            case U8, U16, U32, U64 -> true;
            default -> false;
        };
        ProtoScalarValue scalar = unsigned ? ProtoScalarValue.ofUint64Value(ref) : ProtoScalarValue.ofInt64Value(ref);
        return MemorySegment.ofArray(scalar.encode());
    }

    private static Object residualsAsNativeArray(long[] longs, long ref, PType ptype) {
        int n = longs.length;
        return switch (ptype) {
            case I8, U8 -> {
                byte[] r = new byte[n];
                for (int i = 0; i < n; i++) {
                    r[i] = (byte) (longs[i] - ref);
                }
                yield r;
            }
            case I16, U16 -> {
                short[] r = new short[n];
                for (int i = 0; i < n; i++) {
                    r[i] = (short) (longs[i] - ref);
                }
                yield r;
            }
            case I32, U32 -> {
                int[] r = new int[n];
                for (int i = 0; i < n; i++) {
                    r[i] = (int) (longs[i] - ref);
                }
                yield r;
            }
            case I64, U64 -> {
                long[] r = new long[n];
                for (int i = 0; i < n; i++) {
                    r[i] = longs[i] - ref;
                }
                yield r;
            }
            default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype: " + ptype);
        };
    }

    private static MemorySegment toResidualBuffer(long[] longs, long ref, PType ptype, EncodeContext ctx) {
        int n = longs.length;
        int elemBytes = ptype.byteSize();
        MemorySegment seg = ctx.arena().allocate((long) n * elemBytes, elemBytes);
        for (int i = 0; i < n; i++) {
            long r = longs[i] - ref;
            PTypeIO.set(seg, (long) i * elemBytes, ptype, r);
        }
        return seg;
    }
}
