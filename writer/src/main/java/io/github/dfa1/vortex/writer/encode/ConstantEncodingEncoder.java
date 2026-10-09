package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;

/// Write-only encoder for `vortex.constant`.
public final class ConstantEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_CONSTANT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Bool || dtype instanceof DType.Utf8;
    }

    @Override
    public StatsOptions statsOptions() {
        return new StatsOptions(true, false);
    }

    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        // Rust detects constants in the compressor itself, never on a sample: a constant sample
        // does not imply a constant array (`compressor/cascade.rs`).
        if (ctx.sample()) {
            return Estimate.SKIP;
        }
        if (dtype instanceof DType.Utf8) {
            // Strings carry no stats; Rust's constant check is one pass over the array
            String[] strings = (String[]) data.data();
            return strings.length > 0 && isConstantStrings(strings) ? Estimate.ALWAYS_USE : Estimate.SKIP;
        }
        ArrayStats stats = data.stats();
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
        if (dtype instanceof DType.Bool) {
            return encodeBool((boolean[]) data);
        }
        if (dtype instanceof DType.Utf8) {
            return encodeUtf8((String[]) data);
        }
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "encode only supports Primitive or Bool dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        if (!isConstant(data, ptype)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array");
        }
        long firstRaw = readFirstRaw(data, ptype);
        ProtoScalarValue scalar = buildScalar(ptype, firstRaw);
        byte[] scalarBytes = scalar.encode();
        // A constant array's min and max are both the one repeated value, by construction -- no
        // scan needed. Empty arrays report no stats, matching every other encoder's convention.
        byte[] stats = Array.getLength(data) > 0 ? scalarBytes : null;
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, EncodedBuffer.bytes(MemorySegment.ofArray(scalarBytes)), stats, stats);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (dtype instanceof DType.Utf8) {
            String[] strings = (String[]) data;
            if (strings.length == 0 || !isConstantStrings(strings)) {
                return CascadeStep.notApplicable();
            }
            return CascadeStep.terminal(encode(dtype, data, encodeCtx));
        }
        if (dtype instanceof DType.Bool bool) {
            if (!isConstantBool((boolean[]) data)) {
                return CascadeStep.notApplicable();
            }
            return CascadeStep.terminal(encode(bool, data, encodeCtx));
        }
        if (!isConstant(data, ((DType.Primitive) dtype).ptype())) {
            return CascadeStep.notApplicable();
        }
        return CascadeStep.terminal(encode(dtype, data, encodeCtx));
    }

    private static EncodeResult encodeUtf8(String[] data) {
        if (data.length == 0 || !isConstantStrings(data)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array");
        }
        byte[] scalarBytes = ProtoScalarValue.ofStringValue(data[0]).encode();
        // Both extremes are the one repeated value, by construction
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, EncodedBuffer.bytes(MemorySegment.ofArray(scalarBytes)),
                scalarBytes, scalarBytes);
    }

    private static boolean isConstantStrings(String[] data) {
        for (int i = 1; i < data.length; i++) {
            if (!data[i].equals(data[0])) {
                return false;
            }
        }
        return true;
    }

    private static EncodeResult encodeBool(boolean[] data) {
        if (!isConstantBool(data)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array");
        }
        boolean value = data.length == 0 || data[0];
        ProtoScalarValue scalar = ProtoScalarValue.ofBoolValue(value);
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, EncodedBuffer.bytes(MemorySegment.ofArray(scalar.encode())));
    }

    private static boolean isConstantBool(boolean[] data) {
        if (data.length == 0) {
            return true;
        }
        boolean first = data[0];
        for (boolean b : data) {
            if (b != first) {
                return false;
            }
        }
        return true;
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

    // One typed loop per width, the ptype switch hoisted out (CLAUDE.md hot-loop rule). Floats
    // compare raw bits, so distinct NaN payloads or -0.0 vs 0.0 are not constant.
    private static boolean isConstant(Object data, PType ptype) {
        switch (ptype) {
            case I8, U8 -> {
                byte[] a = (byte[]) data;
                for (int i = 1; i < a.length; i++) {
                    if (a[i] != a[0]) {
                        return false;
                    }
                }
            }
            case I16, U16 -> {
                short[] a = (short[]) data;
                for (int i = 1; i < a.length; i++) {
                    if (a[i] != a[0]) {
                        return false;
                    }
                }
            }
            case I32, U32 -> {
                int[] a = (int[]) data;
                for (int i = 1; i < a.length; i++) {
                    if (a[i] != a[0]) {
                        return false;
                    }
                }
            }
            case I64, U64 -> {
                long[] a = (long[]) data;
                for (int i = 1; i < a.length; i++) {
                    if (a[i] != a[0]) {
                        return false;
                    }
                }
            }
            case F32 -> {
                float[] a = (float[]) data;
                for (int i = 1; i < a.length; i++) {
                    if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(a[0])) {
                        return false;
                    }
                }
            }
            case F64 -> {
                double[] a = (double[]) data;
                for (int i = 1; i < a.length; i++) {
                    if (Double.doubleToRawLongBits(a[i]) != Double.doubleToRawLongBits(a[0])) {
                        return false;
                    }
                }
            }
            default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
        }
        return true;
    }

    private static ProtoScalarValue buildScalar(PType ptype, long rawBits) {
        return switch (ptype) {
            case U8, U16, U32, U64 -> ProtoScalarValue.ofUint64Value(rawBits);
            case I8, I16, I32, I64 -> ProtoScalarValue.ofInt64Value(rawBits);
            case F32 -> ProtoScalarValue.ofF32Value(Float.intBitsToFloat((int) rawBits));
            case F64 -> ProtoScalarValue.ofF64Value(Double.longBitsToDouble(rawBits));
            default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
        };
    }
}
