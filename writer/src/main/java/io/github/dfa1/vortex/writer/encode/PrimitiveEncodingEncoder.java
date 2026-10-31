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
// The stat helpers return the generated ProtoScalarValue, qualified-exported by core: see EncodeResult.
@SuppressWarnings("exports")
public final class PrimitiveEncodingEncoder implements EncodingEncoder {

    /// Creates the encoder.
    public PrimitiveEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PRIMITIVE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    /// A nullable primitive keeps its validity as a `vortex.bool` child, as Rust's canonical
    /// `PrimitiveArray` does.
    @Override
    public boolean acceptsNullable(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (data instanceof NullableData nullable) {
            return encode(dtype, nullable.values(), ctx)
                    .withStats(ZoneMapStats.of(dtype.withNullable(false), nullable))
                    .withValidity(nullable.validity(), ctx);
        }
        PType ptype = ((DType.Primitive) dtype).ptype();
        // byte[] needs no byte swap, so it is wrapped in place rather than copied off-heap.
        MemorySegment seg = data instanceof byte[] bytes
                ? MemorySegment.ofArray(bytes)
                : PrimitiveArrays.toSegment(data, ptype, ctx.arena());
        ProtoScalarValue min = null;
        ProtoScalarValue max = null;
        MinMax stats = minMaxStats(ptype, data);
        if (stats != null) {
            min = stats.min();
            max = stats.max();
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
    /// @return the [MinMax] of encoded scalars, or `null` if `data` is empty
    public static MinMax minMaxStats(PType ptype, Object data) {
        if (Array.getLength(data) == 0) {
            return null;
        }
        // Floating-point arrays skip NaN, so an all-NaN one has no min/max: the kernel returns nothing
        long[] minMax = SimdOperationsSupport.preferred().minMax(data, ptype);
        if (minMax.length == 0) {
            return null;
        }
        return switch (ptype) {
            case I8, I16, I32, I64 -> new MinMax(scalarI64(minMax[0]), scalarI64(minMax[1]));
            case U8, U16, U32, U64 -> new MinMax(scalarU64(minMax[0]), scalarU64(minMax[1]));
            case F16 -> new MinMax(scalarF16(Float.float16ToFloat((short) minMax[0])),
                    scalarF16(Float.float16ToFloat((short) minMax[1])));
            case F32 -> new MinMax(scalarF32(Float.intBitsToFloat((int) minMax[0])),
                    scalarF32(Float.intBitsToFloat((int) minMax[1])));
            case F64 -> new MinMax(scalarF64(Double.longBitsToDouble(minMax[0])),
                    scalarF64(Double.longBitsToDouble(minMax[1])));
        };
    }

    /// The min half of a [#minMaxStats] result, or `null` when `stats` itself is `null` (empty
    /// data). Saves callers the repeated `stats == null ? null : stats.min()` idiom.
    ///
    /// @param stats a [#minMaxStats] result, possibly `null`
    /// @return the serialized min scalar, or `null`
    public static ProtoScalarValue minOf(MinMax stats) {
        return stats == null ? null : stats.min();
    }

    /// The max half of a [#minMaxStats] result, or `null` when `stats` itself is `null` (empty
    /// data). Saves callers the repeated `stats == null ? null : stats.max()` idiom.
    ///
    /// @param stats a [#minMaxStats] result, possibly `null`
    /// @return the serialized max scalar, or `null`
    public static ProtoScalarValue maxOf(MinMax stats) {
        return stats == null ? null : stats.max();
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
    // Most likely dead weight: Rust's default vortex.zoned layout writes no zone sum (#447) and vortex-jni
    // cannot read one, so only the legacy vortex.stats layout and segment/dict-chunk stats carry this value,
    // observable through our own columnZoneStats. Kept NaN-skipping for parity (#523); candidate for removal.
    public static ProtoScalarValue sumStat(PType ptype, Object data) {
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

    private static ProtoScalarValue scalarI64(long v) {
        return ProtoScalarValue.ofInt64Value(v);
    }

    private static ProtoScalarValue scalarU64(long v) {
        return ProtoScalarValue.ofUint64Value(v);
    }

    // Rust types an F16 column's min/max as F16 scalars (`f16_value`, the half bits as a u64); an
    // f32 scalar on an F16 column is rejected on read ("expected F32 dtype for F32Value, got f16").
    private static ProtoScalarValue scalarF16(float v) {
        return ProtoScalarValue.ofF16Value(Short.toUnsignedLong(Float.floatToFloat16(v)));
    }

    private static ProtoScalarValue scalarF32(float v) {
        return ProtoScalarValue.ofF32Value(v);
    }

    private static ProtoScalarValue scalarF64(double v) {
        return ProtoScalarValue.ofF64Value(v);
    }
}
