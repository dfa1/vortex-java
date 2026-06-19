package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// Write-only encoder for `vortex.primitive` — raw little-endian primitive arrays.
public final class PrimitiveEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
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

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        MemorySegment seg = encodePrimitive(ptype, data, ctx.arena());
        byte[] min = null;
        byte[] max = null;
        byte[][] stats = computeStats(ptype, data);
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
                    seg.setAtIndex(PTypeIO.LE_SHORT, i, arr[i]);
                }
                yield seg;
            }
            case I32, U32 -> {
                int[] arr = (int[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 4, 4);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_INT, i, arr[i]);
                }
                yield seg;
            }
            case I64, U64 -> {
                long[] arr = (long[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 8, 8);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_LONG, i, arr[i]);
                }
                yield seg;
            }
            case F32 -> {
                float[] arr = (float[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 4, 4);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_FLOAT, i, arr[i]);
                }
                yield seg;
            }
            case F64 -> {
                double[] arr = (double[]) data;
                MemorySegment seg = arena.allocate((long) arr.length * 8, 8);
                for (int i = 0; i < arr.length; i++) {
                    seg.setAtIndex(PTypeIO.LE_DOUBLE, i, arr[i]);
                }
                yield seg;
            }
        };
    }

    private static byte[][] computeStats(PType ptype, Object data) {
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

    private static byte[] scalarI64(long v) {
        return ScalarValue.ofInt64Value(v).encode();
    }

    private static byte[] scalarU64(long v) {
        return ScalarValue.ofUint64Value(v).encode();
    }

    private static byte[] scalarF32(float v) {
        return ScalarValue.ofF32Value(v).encode();
    }

    private static byte[] scalarF64(double v) {
        return ScalarValue.ofF64Value(v).encode();
    }
}
