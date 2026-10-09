package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSequenceMetadata;

import java.util.List;

/// Write-only encoder for `vortex.sequence` — arithmetic sequences as (base, multiplier).
public final class SequenceEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SEQUENCE;
    }

    /// Rust's `SequenceScheme` verdict (`vortex-btrblocks` `schemes/integer/sequence.rs`).
    ///
    /// Never on a sample: the cascade samples in random strides, so a sample of a sequence is
    /// not one, and a sample that happens to be one says nothing of the array. On the real array,
    /// an exact distinct count other than the length rules it out; otherwise it is checked
    /// directly, and a sequence scores Rust's `len / 2` — two scalars stand for the whole array.
    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        if (ctx.sample()) {
            return Estimate.SKIP;
        }
        ArrayStats stats = data.stats();
        if (stats.hasDistinctCount() && !stats.distinctCapped() && stats.distinctCount() != stats.valueCount()) {
            return Estimate.SKIP;
        }
        if (!encodeCascade(dtype, data.data(), ctx).applicable()) {
            return Estimate.SKIP;
        }
        return Estimate.ratio(stats.valueCount() / 2.0);
    }

    @Override
    public boolean accepts(DType dtype) {
        // Integer-only, as Rust's SequenceScheme: SequenceArray rejects float ptypes on construction.
        return dtype instanceof DType.Primitive p && !p.ptype().isFloating();
    }

    /// Cascade-aware entry point. [#encode] throws when `data` isn't a perfect arithmetic sequence
    /// (e.g. a winner selected on a stratified sample that turns out not to hold over the full
    /// data) — the cascade's retry contract needs a non-applicable [CascadeStep], not an exception,
    /// so failures are caught here and reported that way instead.
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        try {
            return CascadeStep.terminal(encode(dtype, data, ctx));
        } catch (VortexException _) {
            return CascadeStep.notApplicable();
        }
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!accepts(dtype)) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "encode only supports integer Primitive dtype, got " + dtype);
        }
        return encodeInteger(((DType.Primitive) dtype).ptype(), data);
    }

    private static EncodeResult encodeInteger(PType pt, Object data) {
        int n = Array.getLength(data);
        long base = 0;
        long multiplier = 0;
        if (n > 0) {
            base = readLong(pt, data, 0);
            multiplier = n > 1 ? readLong(pt, data, 1) - base : 0;
            for (int i = 2; i < n; i++) {
                long expected = base + i * multiplier;
                if (readLong(pt, data, i) != expected) {
                    throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
                }
            }
        }
        ProtoScalarValue baseScalar = buildIntScalar(pt, base);
        ProtoScalarValue mulScalar = buildIntScalar(pt, multiplier);
        // A perfect arithmetic sequence is monotonic (or constant) end to end, so its extremes are
        // always the first and last element -- no separate scan needed.
        boolean unsign = pt.isUnsigned();
        byte[] statsMin = null;
        byte[] statsMax = null;
        if (n > 0) {
            long last = base + (n - 1) * multiplier;
            boolean baseIsMin = unsign ? Long.compareUnsigned(base, last) <= 0 : base <= last;
            statsMin = buildIntScalar(pt, baseIsMin ? base : last).encode();
            statsMax = buildIntScalar(pt, baseIsMin ? last : base).encode();
        }
        return buildResult(baseScalar, mulScalar, statsMin, statsMax);
    }

    private static EncodeResult buildResult(ProtoScalarValue base, ProtoScalarValue mul, byte[] statsMin, byte[] statsMax) {
        ProtoSequenceMetadata meta = new ProtoSequenceMetadata(base, mul);
        MemorySegment metaBuf = MemorySegment.ofArray(meta.encode());
        EncodeNode node = new EncodeNode(EncodingId.VORTEX_SEQUENCE, metaBuf, new EncodeNode[0], new int[]{});
        return new EncodeResult(node, List.of(), statsMin, statsMax);
    }

    private static ProtoScalarValue buildIntScalar(PType pt, long value) {
        return switch (pt) {
            case U8, U16, U32, U64 -> ProtoScalarValue.ofUint64Value(value);
            default -> ProtoScalarValue.ofInt64Value(value);
        };
    }

    private static long readLong(PType pt, Object data, int i) {
        return switch (pt) {
            case I8, U8 -> ((byte[]) data)[i];
            case I16, U16 -> ((short[]) data)[i];
            case I32, U32 -> ((int[]) data)[i];
            case I64, U64 -> ((long[]) data)[i];
            default -> throw new VortexException(EncodingId.VORTEX_SEQUENCE, "unsupported ptype: " + pt);
        };
    }
}
