package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import java.lang.foreign.MemorySegment;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSequenceMetadata;

import java.util.List;

/// Write-only encoder for `vortex.sequence` — arithmetic sequences as (base, multiplier).
public final class SequenceEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SEQUENCE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
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
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "encode only supports Primitive dtype, got " + dtype);
        }
        PType pt = p.ptype();
        return switch (pt) {
            case I8, I16, I32, I64, U8, U16, U32, U64 -> encodeInteger(pt, data);
            case F32 -> encodeF32((float[]) data);
            case F64 -> encodeF64((double[]) data);
            case F16 -> encodeF16((short[]) data);
        };
    }

    private static EncodeResult encodeInteger(PType pt, Object data) {
        int n = intArrayLength(pt, data);
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

    private static EncodeResult encodeF32(float[] data) {
        float base = data.length > 0 ? data[0] : 0f;
        float mul = data.length > 1 ? data[1] - base : 0f;
        for (int i = 2; i < data.length; i++) {
            if (data[i] != base + i * mul) {
                throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
            }
        }
        byte[] statsMin = null;
        byte[] statsMax = null;
        if (data.length > 0) {
            float last = base + (data.length - 1) * mul;
            statsMin = ProtoScalarValue.ofF32Value(Math.min(base, last)).encode();
            statsMax = ProtoScalarValue.ofF32Value(Math.max(base, last)).encode();
        }
        return buildResult(ProtoScalarValue.ofF32Value(base), ProtoScalarValue.ofF32Value(mul), statsMin, statsMax);
    }

    private static EncodeResult encodeF64(double[] data) {
        double base = data.length > 0 ? data[0] : 0.0;
        double mul = data.length > 1 ? data[1] - base : 0.0;
        for (int i = 2; i < data.length; i++) {
            if (data[i] != base + i * mul) {
                throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
            }
        }
        byte[] statsMin = null;
        byte[] statsMax = null;
        if (data.length > 0) {
            double last = base + (data.length - 1) * mul;
            statsMin = ProtoScalarValue.ofF64Value(Math.min(base, last)).encode();
            statsMax = ProtoScalarValue.ofF64Value(Math.max(base, last)).encode();
        }
        return buildResult(ProtoScalarValue.ofF64Value(base), ProtoScalarValue.ofF64Value(mul), statsMin, statsMax);
    }

    private static EncodeResult encodeF16(short[] data) {
        short baseShort = data.length > 0 ? data[0] : 0;
        float baseF = Float.float16ToFloat(baseShort);
        float mulF = data.length > 1 ? Float.float16ToFloat(data[1]) - baseF : 0f;
        short mulShort = Float.floatToFloat16(mulF);
        for (int i = 2; i < data.length; i++) {
            short expected = Float.floatToFloat16(baseF + i * mulF);
            if (data[i] != expected) {
                throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
            }
        }
        byte[] statsMin = null;
        byte[] statsMax = null;
        if (data.length > 0) {
            float lastF = baseF + (data.length - 1) * mulF;
            short minShort = Float.floatToFloat16(Math.min(baseF, lastF));
            short maxShort = Float.floatToFloat16(Math.max(baseF, lastF));
            statsMin = ProtoScalarValue.ofF16Value(Short.toUnsignedLong(minShort)).encode();
            statsMax = ProtoScalarValue.ofF16Value(Short.toUnsignedLong(maxShort)).encode();
        }
        return buildResult(
                ProtoScalarValue.ofF16Value(Short.toUnsignedLong(baseShort)),
                ProtoScalarValue.ofF16Value(Short.toUnsignedLong(mulShort)),
                statsMin, statsMax);
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

    private static int intArrayLength(PType pt, Object data) {
        return switch (pt) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            default -> throw new VortexException(EncodingId.VORTEX_SEQUENCE, "unsupported ptype: " + pt);
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
