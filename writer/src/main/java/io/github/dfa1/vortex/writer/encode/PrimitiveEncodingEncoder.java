package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperations;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.util.OptionalLong;

/// Write-only encoder for `vortex.primitive` — raw little-endian primitive arrays.
public final class PrimitiveEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PRIMITIVE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        // byte[] needs no byte swap, so it is wrapped in place rather than copied off-heap.
        MemorySegment seg = data instanceof byte[] bytes
                ? MemorySegment.ofArray(bytes)
                : PrimitiveArrays.toSegment(data, ptype, ctx.arena());
        byte[] min = null;
        byte[] max = null;
        byte[][] stats = minMaxStats(ptype, data);
        if (stats != null) {
            min = stats[0];
            max = stats[1];
        }
        return EncodeResult.simple(EncodingId.VORTEX_PRIMITIVE, EncodedBuffer.of(seg, ptype), min, max);
    }

    /// Computes the serialized min/max [io.github.dfa1.vortex.core.proto.ProtoScalarValue] pair for a raw
    /// primitive array, in the same signed/unsigned/float shape the per-segment stats use. Returns
    /// `null` for an empty array. Shared so the dictionary zone-map path computes per-chunk min/max
    /// identically to the flat path.
    ///
    /// @param ptype the primitive type of `data`
    /// @param data  the raw primitive array (e.g. `long[]`, `int[]`, `String`-free)
    /// @return a two-element `{min, max}` array of encoded scalars, or `null` if `data` is empty
    public static byte[][] minMaxStats(PType ptype, Object data) {
        if (Array.getLength(data) == 0) {
            return null;
        }
        // Floating-point arrays skip NaN, so an all-NaN one has no min/max: the kernel returns nothing
        long[] minMax = SimdOperationsSupport.preferred().minMax(data, ptype);
        if (minMax.length == 0) {
            return null;
        }
        return switch (ptype) {
            case I8, I16, I32, I64 -> new byte[][]{scalarI64(minMax[0]), scalarI64(minMax[1])};
            case U8, U16, U32, U64 -> new byte[][]{scalarU64(minMax[0]), scalarU64(minMax[1])};
            case F16 -> new byte[][]{scalarF16(Float.float16ToFloat((short) minMax[0])),
                    scalarF16(Float.float16ToFloat((short) minMax[1]))};
            case F32 -> new byte[][]{scalarF32(Float.intBitsToFloat((int) minMax[0])),
                    scalarF32(Float.intBitsToFloat((int) minMax[1]))};
            case F64 -> new byte[][]{scalarF64(Double.longBitsToDouble(minMax[0])),
                    scalarF64(Double.longBitsToDouble(minMax[1]))};
        };
    }

    /// The min half of a [#minMaxStats] result, or `null` when `stats` itself is `null` (empty
    /// data). Saves callers the repeated `stats == null ? null : stats[0]` idiom.
    ///
    /// @param stats a [#minMaxStats] result, possibly `null`
    /// @return the serialized min scalar, or `null`
    public static byte[] minOf(byte[][] stats) {
        return stats == null ? null : stats[0];
    }

    /// The max half of a [#minMaxStats] result, or `null` when `stats` itself is `null` (empty
    /// data). Saves callers the repeated `stats == null ? null : stats[1]` idiom.
    ///
    /// @param stats a [#minMaxStats] result, possibly `null`
    /// @return the serialized max scalar, or `null`
    public static byte[] maxOf(byte[][] stats) {
        return stats == null ? null : stats[1];
    }

    /// Computes the serialized SUM [io.github.dfa1.vortex.core.proto.ProtoScalarValue] for a raw primitive
    /// array, in the widened shape Rust uses for zone-map sums: signed ints → `i64`, unsigned ints
    /// → `u64`, floats → `f64`. Returns `null` on integer overflow (Rust drops the zone's sum) and
    /// for an empty array. Floats never overflow to `null` (they saturate to infinity).
    ///
    /// Nulls need not be excluded by the caller: validity placeholders are zero, which is
    /// sum-neutral — matching the per-segment min/max convention.
    ///
    /// @param ptype the primitive type of `data`
    /// @param data  the raw primitive array
    /// @return the encoded sum scalar, or `null` on overflow or empty input
    public static byte[] sumStat(PType ptype, Object data) {
        if (Array.getLength(data) == 0) {
            return null;
        }
        SimdOperations ops = SimdOperationsSupport.preferred();
        return switch (ptype) {
            case I8, I16, I32, I64 -> {
                OptionalLong sum = ops.sum(data, ptype);
                yield sum.isEmpty() ? null : scalarI64(sum.getAsLong());
            }
            case U8, U16, U32, U64 -> {
                OptionalLong sum = ops.sum(data, ptype);
                yield sum.isEmpty() ? null : scalarU64(sum.getAsLong());
            }
            case F16, F32, F64 -> scalarF64(ops.sumFloating(data, ptype));
        };
    }

    private static byte[] scalarI64(long v) {
        return ProtoScalarValue.ofInt64Value(v).encode();
    }

    private static byte[] scalarU64(long v) {
        return ProtoScalarValue.ofUint64Value(v).encode();
    }

    // Rust types an F16 column's min/max as F16 scalars (`f16_value`, the half bits as a u64); an
    // f32 scalar on an F16 column is rejected on read ("expected F32 dtype for F32Value, got f16").
    private static byte[] scalarF16(float v) {
        return ProtoScalarValue.ofF16Value(Short.toUnsignedLong(Float.floatToFloat16(v))).encode();
    }

    private static byte[] scalarF32(float v) {
        return ProtoScalarValue.ofF32Value(v).encode();
    }

    private static byte[] scalarF64(double v) {
        return ProtoScalarValue.ofF64Value(v).encode();
    }
}
