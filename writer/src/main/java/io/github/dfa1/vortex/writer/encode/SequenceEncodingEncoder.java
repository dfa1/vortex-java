package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import java.lang.foreign.MemorySegment;
import io.github.dfa1.vortex.proto.ProtoScalarValue;
import io.github.dfa1.vortex.proto.ProtoSequenceMetadata;

import java.util.List;

/// Write-only encoder for `vortex.sequence` — arithmetic sequences as (base, multiplier).
public final class SequenceEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public SequenceEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SEQUENCE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
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
        return buildResult(baseScalar, mulScalar);
    }

    private static EncodeResult encodeF32(float[] data) {
        float base = data.length > 0 ? data[0] : 0f;
        float mul = data.length > 1 ? data[1] - base : 0f;
        for (int i = 2; i < data.length; i++) {
            if (data[i] != base + i * mul) {
                throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
            }
        }
        return buildResult(ProtoScalarValue.ofF32Value(base), ProtoScalarValue.ofF32Value(mul));
    }

    private static EncodeResult encodeF64(double[] data) {
        double base = data.length > 0 ? data[0] : 0.0;
        double mul = data.length > 1 ? data[1] - base : 0.0;
        for (int i = 2; i < data.length; i++) {
            if (data[i] != base + i * mul) {
                throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
            }
        }
        return buildResult(ProtoScalarValue.ofF64Value(base), ProtoScalarValue.ofF64Value(mul));
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
        return buildResult(
                ProtoScalarValue.ofF16Value(Short.toUnsignedLong(baseShort)),
                ProtoScalarValue.ofF16Value(Short.toUnsignedLong(mulShort)));
    }

    private static EncodeResult buildResult(ProtoScalarValue base, ProtoScalarValue mul) {
        ProtoSequenceMetadata meta = new ProtoSequenceMetadata(base, mul);
        MemorySegment metaBuf = MemorySegment.ofArray(meta.encode());
        EncodeNode node = new EncodeNode(EncodingId.VORTEX_SEQUENCE, metaBuf, new EncodeNode[0], new int[]{});
        return new EncodeResult(node, List.of(), null, null);
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
