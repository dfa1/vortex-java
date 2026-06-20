package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.MemorySegment;

/// Write-only encoder for `vortex.constant`.
public final class ConstantEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public ConstantEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_CONSTANT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public StatsOptions statsOptions() {
        return new StatsOptions(true, false);
    }

    @Override
    public Estimate expectedRatio(DType dtype, Object data, ArrayStats stats) {
        if (stats.valueCount() == 0) {
            return Estimate.ALWAYS_USE;
        }
        if (!stats.hasDistinctCount()) {
            return Estimate.COMPLETE;
        }
        if (stats.distinctCount() == 1) {
            return Estimate.ALWAYS_USE;
        }
        return Estimate.SKIP;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "encode only supports Primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        if (!isConstant(data, ptype)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array");
        }
        long firstRaw = readFirstRaw(data, ptype);
        ScalarValue scalar = buildScalar(ptype, firstRaw);
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, MemorySegment.ofArray(scalar.encode()));
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (!isConstant(data, ((DType.Primitive) dtype).ptype())) {
            return CascadeStep.notApplicable();
        }
        return CascadeStep.terminal(encode(dtype, data, encodeCtx));
    }

    private static long readFirstRaw(Object data, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length > 0 ? ((byte[]) data)[0] : 0L;
            case I16, U16 -> ((short[]) data).length > 0 ? ((short[]) data)[0] : 0L;
            case I32, U32 -> ((int[]) data).length > 0 ? ((int[]) data)[0] : 0L;
            case I64, U64 -> ((long[]) data).length > 0 ? ((long[]) data)[0] : 0L;
            case F32 -> ((float[]) data).length > 0 ? Float.floatToRawIntBits(((float[]) data)[0]) : 0L;
            case F64 -> ((double[]) data).length > 0 ? Double.doubleToRawLongBits(((double[]) data)[0]) : 0L;
            default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
        };
    }

    private static boolean isConstant(Object data, PType ptype) {
        long firstRaw = readFirstRaw(data, ptype);
        int len = switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F32 -> ((float[]) data).length;
            case F64 -> ((double[]) data).length;
            default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
        };
        for (int i = 1; i < len; i++) {
            long raw = switch (ptype) {
                case I8, U8 -> ((byte[]) data)[i];
                case I16, U16 -> ((short[]) data)[i];
                case I32, U32 -> ((int[]) data)[i];
                case I64, U64 -> ((long[]) data)[i];
                case F32 -> Float.floatToRawIntBits(((float[]) data)[i]);
                case F64 -> Double.doubleToRawLongBits(((double[]) data)[i]);
                default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
            };
            if (raw != firstRaw) {
                return false;
            }
        }
        return true;
    }

    private static ScalarValue buildScalar(PType ptype, long rawBits) {
        return switch (ptype) {
            case U8, U16, U32, U64 -> ScalarValue.ofUint64Value(rawBits);
            case I8, I16, I32, I64 -> ScalarValue.ofInt64Value(rawBits);
            case F32 -> ScalarValue.ofF32Value(Float.intBitsToFloat((int) rawBits));
            case F64 -> ScalarValue.ofF64Value(Double.longBitsToDouble(rawBits));
            default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
        };
    }
}
