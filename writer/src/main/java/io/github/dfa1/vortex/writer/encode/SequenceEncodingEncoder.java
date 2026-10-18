package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.simd.SimdOperations;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
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

    /// Elements widened per step of [#encodeInteger(PType, Object)]'s scan.
    private static final int WIDEN_BLOCK = 1024;


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
        return dtype instanceof DType.Primitive p && p.ptype().isInteger();
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

    /// Rust's `encode_primitive_array`: `base` is the first value and the multiplier
    /// `slice[1].checked_sub(base)` in the column's own type, so a step that does not fit it (a
    /// decreasing unsigned column, an `i8` step of 255) is no sequence; nor is an empty array or a
    /// zero step (constant data, left to the constant encoding); every later step is a
    /// `checked_add` in that type too. Values widen zero-extended for unsigned types: a
    /// sign-extended `u16` became a `u64` scalar Rust refuses ("out of range for dtype u16").
    private static EncodeResult encodeInteger(PType pt, Object data) {
        int n = Array.getLength(data);
        if (n == 0) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "an empty array is not a sequence");
        }
        // Widened a block at a time: the cascade tries this on every integer column, and most fail
        // by their third value, so neither a column-sized copy nor a full scan is spent on them.
        long[] block = new long[Math.min(n, WIDEN_BLOCK)];
        SimdOperations ops = SimdOperationsSupport.preferred();
        long base = 0;
        long multiplier = 0;
        long previous = 0;
        for (int from = 0; from < n; from += block.length) {
            int count = Math.min(block.length, n - from);
            ops.widenArrayInto(data, from, count, pt, block);
            for (int j = 0; j < count; j++) {
                int i = from + j;
                long value = block[j];
                if (i == 0) {
                    base = value;
                } else if (i == 1) {
                    multiplier = checkedSub(pt, value, base);
                    if (multiplier == 0) {
                        throw new VortexException(EncodingId.VORTEX_SEQUENCE, "a zero step is not a sequence");
                    }
                } else if (value != checkedAdd(pt, previous, multiplier)) {
                    throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
                }
                previous = value;
            }
        }
        long last = previous;
        boolean baseIsMin = pt.isUnsigned() ? Long.compareUnsigned(base, last) <= 0 : base <= last;
        return buildResult(buildIntScalar(pt, base), buildIntScalar(pt, multiplier),
                buildIntScalar(pt, baseIsMin ? base : last), buildIntScalar(pt, baseIsMin ? last : base));
    }

    /// `a - b` in `pt`, or not applicable when the result does not fit `pt`.
    private static long checkedSub(PType pt, long a, long b) {
        return switch (pt) {
            case U64 -> {
                if (Long.compareUnsigned(a, b) < 0) {
                    throw notInType(pt);
                }
                yield a - b;
            }
            case I64 -> {
                try {
                    yield Math.subtractExact(a, b);
                } catch (ArithmeticException _) {
                    throw notInType(pt);
                }
            }
            default -> fitting(pt, a - b);
        };
    }

    /// `a + b` in `pt`, or not applicable when the result does not fit `pt`.
    private static long checkedAdd(PType pt, long a, long b) {
        return switch (pt) {
            case U64 -> {
                long sum = a + b;
                if (Long.compareUnsigned(sum, a) < 0) {
                    throw notInType(pt);
                }
                yield sum;
            }
            case I64 -> {
                try {
                    yield Math.addExact(a, b);
                } catch (ArithmeticException _) {
                    throw notInType(pt);
                }
            }
            default -> fitting(pt, a + b);
        };
    }

    /// `value` (exact in a `long` for widths below 64 bits) if it lies in `pt`'s range.
    private static long fitting(PType pt, long value) {
        long bits = pt.bits();
        long min = pt.isUnsigned() ? 0 : -(1L << (bits - 1));
        long max = pt.isUnsigned() ? (1L << bits) - 1 : (1L << (bits - 1)) - 1;
        if (value < min || value > max) {
            throw notInType(pt);
        }
        return value;
    }

    private static VortexException notInType(PType pt) {
        return new VortexException(EncodingId.VORTEX_SEQUENCE, "sequence step overflows " + pt);
    }

    private static EncodeResult buildResult(ProtoScalarValue base, ProtoScalarValue mul, ProtoScalarValue statsMin, ProtoScalarValue statsMax) {
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

}
