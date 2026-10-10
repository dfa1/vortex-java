package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;

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
        return switch (ptype) {
            case I8, I16, I32, I64 -> {
                if (Array.getLength(data) == 0) {
                    yield null;
                }
                long[] minMax = SimdOperationsSupport.preferred().minMax(data, ptype);
                yield new byte[][]{scalarI64(minMax[0]), scalarI64(minMax[1])};
            }
            // Zero-extended, so the kernel's signed order is their natural order
            case U8, U16, U32 -> {
                if (Array.getLength(data) == 0) {
                    yield null;
                }
                long[] minMax = SimdOperationsSupport.preferred().minMax(data, ptype);
                yield new byte[][]{scalarU64(minMax[0]), scalarU64(minMax[1])};
            }
            case U64 -> {
                long[] arr = (long[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = arr[0];
                long max = arr[0];
                for (long v : arr) {
                    if (Long.compareUnsigned(v, min) < 0) {
                        min = v;
                    }
                    if (Long.compareUnsigned(v, max) > 0) {
                        max = v;
                    }
                }
                yield new byte[][]{scalarU64(min), scalarU64(max)};
            }
            case F32 -> {
                float[] arr = (float[]) data;
                // NaN-skipping, as Rust's min/max (skip_nans): every comparison with NaN is false, so
                // starting from the infinities skips NaN without a per-element branch; an all-NaN
                // (or empty) array ends with min > max and has no min/max.
                float min = Float.POSITIVE_INFINITY;
                float max = Float.NEGATIVE_INFINITY;
                for (float v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield min > max ? null : new byte[][]{scalarF32(min), scalarF32(max)};
            }
            case F64 -> {
                double[] arr = (double[]) data;
                // NaN-skipping, as Rust's min/max (skip_nans): every comparison with NaN is false, so
                // starting from the infinities skips NaN without a per-element branch; an all-NaN
                // (or empty) array ends with min > max and has no min/max.
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (double v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield min > max ? null : new byte[][]{scalarF64(min), scalarF64(max)};
            }
            case F16 -> {
                short[] arr = (short[]) data;
                // NaN-skipping like F32/F64 above.
                float min = Float.POSITIVE_INFINITY;
                float max = Float.NEGATIVE_INFINITY;
                for (short v : arr) {
                    float fv = Float.float16ToFloat(v);
                    if (fv < min) {
                        min = fv;
                    }
                    if (fv > max) {
                        max = fv;
                    }
                }
                yield min > max ? null : new byte[][]{scalarF16(min), scalarF16(max)};
            }
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
        return switch (ptype) {
            case I8 -> {
                byte[] a = (byte[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (byte v : a) {
                    s += v;
                }
                yield scalarI64(s);
            }
            case I16 -> {
                short[] a = (short[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (short v : a) {
                    s += v;
                }
                yield scalarI64(s);
            }
            case I32 -> {
                int[] a = (int[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (int v : a) {
                    s += v;
                }
                yield scalarI64(s);
            }
            case I64 -> {
                long[] a = (long[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (long v : a) {
                    try {
                        s = Math.addExact(s, v);
                    } catch (ArithmeticException _) {
                        yield null;
                    }
                }
                yield scalarI64(s);
            }
            case U8 -> {
                byte[] a = (byte[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (byte v : a) {
                    s += Byte.toUnsignedLong(v);
                }
                yield scalarU64(s);
            }
            case U16 -> {
                short[] a = (short[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (short v : a) {
                    s += Short.toUnsignedLong(v);
                }
                yield scalarU64(s);
            }
            case U32 -> {
                int[] a = (int[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (int v : a) {
                    s += Integer.toUnsignedLong(v);
                }
                yield scalarU64(s);
            }
            case U64 -> {
                long[] a = (long[]) data;
                if (a.length == 0) {
                    yield null;
                }
                long s = 0;
                for (long v : a) {
                    long next = s + v;
                    if (Long.compareUnsigned(next, s) < 0) {
                        yield null;
                    }
                    s = next;
                }
                yield scalarU64(s);
            }
            case F32 -> {
                float[] a = (float[]) data;
                if (a.length == 0) {
                    yield null;
                }
                double s = 0;
                for (float v : a) {
                    s += v;
                }
                yield scalarF64(s);
            }
            case F64 -> {
                double[] a = (double[]) data;
                if (a.length == 0) {
                    yield null;
                }
                double s = 0;
                for (double v : a) {
                    s += v;
                }
                yield scalarF64(s);
            }
            case F16 -> {
                short[] a = (short[]) data;
                if (a.length == 0) {
                    yield null;
                }
                double s = 0;
                for (short v : a) {
                    s += Float.float16ToFloat(v);
                }
                yield scalarF64(s);
            }
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
