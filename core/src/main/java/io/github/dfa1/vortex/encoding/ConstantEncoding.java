package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.NullArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/// Decoder for {@code vortex.constant} — all elements share the same value.
///
/// <p>No metadata (empty bytes). Buffer 0: the constant value as raw {@code ScalarValue}
/// proto bytes. No children.
///
/// <p>Decode: fill an output buffer of {@code rowCount} elements with the constant value.
public final class ConstantEncoding implements Encoding {

    /// Creates a new {@code ConstantEncoding} instance.
    public ConstantEncoding() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext encodeCtx) {
        return Encoder.encode(dtype, data, encodeCtx);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (!Encoder.isConstant(data, ((DType.Primitive) dtype).ptype())) {
            return CascadeStep.notApplicable();
        }
        return CascadeStep.terminal(Encoder.encode(dtype, data, encodeCtx));
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
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

        static boolean isConstant(Object data, PType ptype) {
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

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            MemorySegment scalarBuf = ctx.buffer(0);
            ScalarValue scalar;
            try {
                scalar = ScalarValue.decode(scalarBuf, 0, scalarBuf.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_CONSTANT, "invalid scalar value", e);
            }

            long n = ctx.rowCount();

            if (ctx.dtype() instanceof DType.Null) {
                return new NullArray(ctx.dtype(), n);
            }

            if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
                return decodeString(ctx, scalar, n);
            }

            if (ctx.dtype() instanceof DType.Bool) {
                return decodeBool(ctx, scalar, n);
            }

            if (ctx.dtype() instanceof DType.Decimal) {
                return decodeDecimal(ctx, scalar, n);
            }

            if (ctx.dtype() instanceof DType.Extension ext) {
                // Decode using the storage dtype, re-wrap with the extension dtype
                var storageCtx = new DecodeContext(ctx.node(), ext.storageDType(), ctx.rowCount(),
                    ctx.segmentBuffers(), ctx.registry(), ctx.arena());
                Array storage = decode(storageCtx);
                return new GenericArray(ctx.dtype(), n, ArraySegments.of(storage));
            }

            if (!(ctx.dtype() instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported dtype " + ctx.dtype());
            }

            PType ptype = p.ptype();
            int elemBytes = ptype.byteSize();
            long rawBits = scalarToRawBits(scalar, ptype);

            // Store one element only. The array reports length=n but the buffer holds
            // a single copy of the constant — callers that need all n values must
            // replicate from index 0. This keeps allocation O(1) regardless of rowCount,
            // which also eliminates the zip-bomb vector via inflated row_count.
            MemorySegment outSeg = ctx.arena().allocate(elemBytes);
            ByteBuffer out = outSeg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            writeRaw(out, ptype, rawBits);

            MemorySegment ro = outSeg.asReadOnly();
            return switch (ptype) {
                case I64, U64 -> new LongArray(ctx.dtype(), n, ro);
                case I32, U32 -> new IntArray(ctx.dtype(), n, ro);
                case F64 -> new DoubleArray(ctx.dtype(), n, ro);
                case F32 -> new FloatArray(ctx.dtype(), n, ro);
                case I16, U16 -> new ShortArray(ctx.dtype(), n, ro);
                case I8, U8 -> new ByteArray(ctx.dtype(), n, ro);
                default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
            };
        }

        private static Array decodeDecimal(DecodeContext ctx, ScalarValue scalar, long n) {
            // Decimal stored as i128 (16 bytes LE) in bytes_value
            byte[] elemBytes = scalar.bytes_value();
            int elemLen = elemBytes.length;
            MemorySegment outSeg = ctx.arena().allocate(n * elemLen);
            MemorySegment elemSeg = MemorySegment.ofArray(elemBytes);
            for (long i = 0; i < n; i++) {
                MemorySegment.copy(elemSeg, 0L, outSeg, i * elemLen, elemLen);
            }
            return new GenericArray(ctx.dtype(), n, outSeg.asReadOnly());
        }

        private static Array decodeBool(DecodeContext ctx, ScalarValue scalar, long n) {
            boolean value = scalar.bool_value() != null && scalar.bool_value();
            long numBytes = (n + 7) >>> 3;
            MemorySegment seg = ctx.arena().allocate(numBytes);
            if (value) {
                for (long i = 0; i < numBytes; i++) {
                    seg.set(ValueLayout.JAVA_BYTE, i, (byte) 0xFF);
                }
            }
            return new BoolArray(ctx.dtype(), n, seg.asReadOnly());
        }

        private static Array decodeString(DecodeContext ctx, ScalarValue scalar, long n) {
            byte[] strBytes = scalar.string_value() != null
                                  ? scalar.string_value().getBytes(StandardCharsets.UTF_8)
                                  : (scalar.bytes_value() != null ? scalar.bytes_value() : new byte[0]);

            int strLen = strBytes.length;

            MemorySegment bytesSeg = ctx.arena().allocate((long) n * strLen);
            for (long i = 0; i < n; i++) {
                MemorySegment.copy(MemorySegment.ofArray(strBytes), 0L, bytesSeg, i * strLen, strLen);
            }

            MemorySegment offsetsSeg = ctx.arena().allocate((n + 1) * 4L, 4);
            for (long i = 0; i <= n; i++) {
                offsetsSeg.setAtIndex(PTypeIO.LE_INT, i, (int) (i * strLen));
            }

            return new VarBinArray.OffsetMode(ctx.dtype(), n, bytesSeg.asReadOnly(), offsetsSeg.asReadOnly(), PType.I32);
        }

        private static long scalarToRawBits(ScalarValue scalar, PType ptype) {
            if (scalar.int64_value() != null) {
                return scalar.int64_value();
            }
            if (scalar.uint64_value() != null) {
                return scalar.uint64_value();
            }
            if (scalar.f32_value() != null) {
                return Float.floatToRawIntBits(scalar.f32_value());
            }
            if (scalar.f64_value() != null) {
                return Double.doubleToRawLongBits(scalar.f64_value());
            }
            return 0L;
        }

        private static void writeRaw(ByteBuffer buf, PType ptype, long rawBits) {
            switch (ptype.byteSize()) {
                case 1 -> buf.put((byte) rawBits);
                case 2 -> buf.putShort((short) rawBits);
                case 4 -> buf.putInt((int) rawBits);
                case 8 -> buf.putLong(rawBits);
                default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
            }
        }
    }
}
