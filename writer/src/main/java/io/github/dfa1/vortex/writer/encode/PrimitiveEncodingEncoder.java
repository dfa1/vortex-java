package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

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
        MemorySegment seg = encodePrimitive(ptype, data, ctx.arena());
        byte[] min = null;
        byte[] max = null;
        byte[][] stats = minMaxStats(ptype, data);
        if (stats != null) {
            min = stats[0];
            max = stats[1];
        }
        return EncodeResult.simple(EncodingId.VORTEX_PRIMITIVE, seg, min, max);
    }

    private static MemorySegment encodePrimitive(PType ptype, Object data, Arena arena) {
        return switch (ptype) {
            case I8, U8 -> MemorySegment.ofArray((byte[]) data);
            case I16, U16, F16 -> {
                short[] arr = (short[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 2, 2);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(VortexFormat.LE_SHORT, i, arr[i]);
                }
                yield seg;
            }
            case I32, U32 -> {
                int[] arr = (int[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 4, 4);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(VortexFormat.LE_INT, i, arr[i]);
                }
                yield seg;
            }
            case I64, U64 -> {
                long[] arr = (long[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 8, 8);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(VortexFormat.LE_LONG, i, arr[i]);
                }
                yield seg;
            }
            case F32 -> {
                float[] arr = (float[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 4, 4);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(VortexFormat.LE_FLOAT, i, arr[i]);
                }
                yield seg;
            }
            case F64 -> {
                double[] arr = (double[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 8, 8);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(VortexFormat.LE_DOUBLE, i, arr[i]);
                }
                yield seg;
            }
        };
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
            case I8 -> {
                byte[] arr = (byte[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = arr[0];
                long max = arr[0];
                for (byte v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield new byte[][]{scalarI64(min), scalarI64(max)};
            }
            case I16 -> {
                short[] arr = (short[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = arr[0];
                long max = arr[0];
                for (short v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield new byte[][]{scalarI64(min), scalarI64(max)};
            }
            case I32 -> {
                int[] arr = (int[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = arr[0];
                long max = arr[0];
                for (int v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield new byte[][]{scalarI64(min), scalarI64(max)};
            }
            case I64 -> {
                long[] arr = (long[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = arr[0];
                long max = arr[0];
                for (long v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield new byte[][]{scalarI64(min), scalarI64(max)};
            }
            case U8 -> {
                byte[] arr = (byte[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = Byte.toUnsignedInt(arr[0]);
                long max = Byte.toUnsignedInt(arr[0]);
                for (byte v : arr) {
                    long uv = Byte.toUnsignedInt(v);
                    if (uv < min) {
                        min = uv;
                    }
                    if (uv > max) {
                        max = uv;
                    }
                }
                yield new byte[][]{scalarU64(min), scalarU64(max)};
            }
            case U16 -> {
                short[] arr = (short[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = Short.toUnsignedInt(arr[0]);
                long max = Short.toUnsignedInt(arr[0]);
                for (short v : arr) {
                    long uv = Short.toUnsignedInt(v);
                    if (uv < min) {
                        min = uv;
                    }
                    if (uv > max) {
                        max = uv;
                    }
                }
                yield new byte[][]{scalarU64(min), scalarU64(max)};
            }
            case U32 -> {
                int[] arr = (int[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                long min = Integer.toUnsignedLong(arr[0]);
                long max = Integer.toUnsignedLong(arr[0]);
                for (int v : arr) {
                    long uv = Integer.toUnsignedLong(v);
                    if (uv < min) {
                        min = uv;
                    }
                    if (uv > max) {
                        max = uv;
                    }
                }
                yield new byte[][]{scalarU64(min), scalarU64(max)};
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
                if (arr.length == 0) {
                    yield null;
                }
                float min = arr[0];
                float max = arr[0];
                for (float v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield new byte[][]{scalarF32(min), scalarF32(max)};
            }
            case F64 -> {
                double[] arr = (double[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                double min = arr[0];
                double max = arr[0];
                for (double v : arr) {
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                }
                yield new byte[][]{scalarF64(min), scalarF64(max)};
            }
            case F16 -> {
                short[] arr = (short[]) data;
                if (arr.length == 0) {
                    yield null;
                }
                float min = Float.float16ToFloat(arr[0]);
                float max = Float.float16ToFloat(arr[0]);
                for (short v : arr) {
                    float fv = Float.float16ToFloat(v);
                    if (fv < min) {
                        min = fv;
                    }
                    if (fv > max) {
                        max = fv;
                    }
                }
                yield new byte[][]{scalarF32(min), scalarF32(max)};
            }
        };
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
                    } catch (ArithmeticException overflow) {
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

    private static byte[] scalarF32(float v) {
        return ProtoScalarValue.ofF32Value(v).encode();
    }

    private static byte[] scalarF64(double v) {
        return ProtoScalarValue.ofF64Value(v).encode();
    }
}
