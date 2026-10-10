package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.util.OptionalLong;

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
        return StatsOptions.defaults().withCountDistinct(true);
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
        long firstRaw = constantBits(data, ptype)
                .orElseThrow(() -> new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array"));
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
            if (!SimdOperationsSupport.preferred().allEqual((boolean[]) data)) {
                return CascadeStep.notApplicable();
            }
            return CascadeStep.terminal(encode(bool, data, encodeCtx));
        }
        if (constantBits(data, ((DType.Primitive) dtype).ptype()).isEmpty()) {
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
        if (!SimdOperationsSupport.preferred().allEqual(data)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array");
        }
        boolean value = data.length == 0 || data[0];
        ProtoScalarValue scalar = ProtoScalarValue.ofBoolValue(value);
        return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, EncodedBuffer.bytes(MemorySegment.ofArray(scalar.encode())));
    }

    // The raw bits of the one repeated value, or empty if the array is not constant. An empty array
    // is constant (bits 0), as `expectedRatio` already treats valueCount 0. Floats compare raw bits,
    // so distinct NaN payloads or -0.0 vs 0.0 are not constant.
    private static OptionalLong constantBits(Object data, PType ptype) {
        if (Array.getLength(data) == 0) {
            return OptionalLong.of(0L);
        }
        if (!SimdOperationsSupport.preferred().allEqual(data, ptype)) {
            return OptionalLong.empty();
        }
        // No default: every PType is handled, so a new one fails to compile here instead of at write time
        return OptionalLong.of(switch (ptype) {
            case I8, U8 -> ((byte[]) data)[0];
            case I16, U16, F16 -> ((short[]) data)[0];
            case I32, U32 -> ((int[]) data)[0];
            case I64, U64 -> ((long[]) data)[0];
            case F32 -> Float.floatToRawIntBits(((float[]) data)[0]);
            case F64 -> Double.doubleToRawLongBits(((double[]) data)[0]);
        });
    }

    private static ProtoScalarValue buildScalar(PType ptype, long rawBits) {
        return switch (ptype) {
            case U8, U16, U32, U64 -> ProtoScalarValue.ofUint64Value(rawBits);
            case I8, I16, I32, I64 -> ProtoScalarValue.ofInt64Value(rawBits);
            case F16 -> ProtoScalarValue.ofF16Value(Short.toUnsignedLong((short) rawBits));
            case F32 -> ProtoScalarValue.ofF32Value(Float.intBitsToFloat((int) rawBits));
            case F64 -> ProtoScalarValue.ofF64Value(Double.longBitsToDouble(rawBits));
        };
    }
}
