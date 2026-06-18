package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.GenericArray;
import io.github.dfa1.vortex.reader.array.LazyConstantBoolArray;
import io.github.dfa1.vortex.reader.array.LazyConstantByteArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDecimalArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyConstantFloatArray;
import io.github.dfa1.vortex.reader.array.LazyConstantIntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantLongArray;
import io.github.dfa1.vortex.reader.array.LazyConstantShortArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/// Read-only decoder for `vortex.constant`.
public final class ConstantEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ConstantEncodingDecoder() {
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
    public Array decode(DecodeContext ctx) {
        MemorySegment scalarBuf = ctx.buffer(0);
        ScalarValue scalar;
        try {
            scalar = ScalarValue.decode(scalarBuf, 0, scalarBuf.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "invalid scalar value", e);
        }

        return arrayFromScalar(ctx, scalar, ctx.dtype(), ctx.rowCount());
    }

    /// Builds the constant array for `scalar` interpreted as `dtype`, broadcast to `n` rows.
    /// Recurses for Extension (its storage dtype) and Variant (the wrapped inner scalar).
    private static Array arrayFromScalar(DecodeContext ctx, ScalarValue scalar, DType dtype, long n) {
        if (dtype instanceof DType.Null) {
            return new NullArray(dtype, n);
        }
        if (dtype instanceof DType.Variant) {
            // A constant variant wraps a typed inner scalar (Scalar::variant(inner)); the
            // physical storage is the inner-typed constant array. The VariantArray wrapper
            // re-applies the logical Variant dtype.
            io.github.dfa1.vortex.proto.Scalar inner = scalar.variant_value();
            if (inner == null || inner.value() == null) {
                throw new VortexException(EncodingId.VORTEX_CONSTANT, "constant variant missing variant_value");
            }
            DType innerDtype = VariantEncodingDecoder.dtypeFromProto(inner.dtype());
            return arrayFromScalar(ctx, inner.value(), innerDtype, n);
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            return decodeString(ctx, scalar, dtype, n);
        }
        if (dtype instanceof DType.Bool) {
            return decodeBool(dtype, scalar, n);
        }
        if (dtype instanceof DType.Decimal) {
            return decodeDecimal(dtype, scalar, n);
        }
        if (dtype instanceof DType.Extension ext) {
            Array storage = arrayFromScalar(ctx, scalar, ext.storageDType(), n);
            // GenericArray needs a backing buffer; the recursive call returns a metadata-only
            // LazyConstantXxxArray. Materialise once into the chunk arena so downstream
            // extension consumers that read via ArraySegments.of(arr) still find a segment.
            // Extension-on-constant is rare enough that the small alloc doesn't matter — the
            // bare primitive path stays buffer-free.
            return new GenericArray(dtype, n, ArraySegments.of(storage, ctx.arena()));
        }
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported dtype " + dtype);
        }

        PType ptype = p.ptype();
        long rawBits = scalarToRawBits(scalar, ptype);

        return switch (ptype) {
            case I64, U64 -> new LazyConstantLongArray(dtype, n, rawBits);
            case I32, U32 -> new LazyConstantIntArray(dtype, n, (int) rawBits);
            case F64 -> new LazyConstantDoubleArray(dtype, n, Double.longBitsToDouble(rawBits));
            case F32 -> new LazyConstantFloatArray(dtype, n, Float.intBitsToFloat((int) rawBits));
            case I16, U16 -> new LazyConstantShortArray(dtype, n, (short) rawBits);
            case I8, U8 -> new LazyConstantByteArray(dtype, n, (byte) rawBits);
            default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
        };
    }

    private static Array decodeDecimal(DType dtype, ScalarValue scalar, long n) {
        byte[] elemBytes = scalar.bytes_value();
        int elemLen = elemBytes.length;
        // Decode the single scalar value via LazyDecimalArray (reuses its LE byte-order logic),
        // then wrap in a constant array — O(1) allocation regardless of row count.
        var value = new LazyDecimalArray(dtype, 1, MemorySegment.ofArray(elemBytes), elemLen).getDecimal(0);
        return new LazyConstantDecimalArray(dtype, n, value, elemLen);
    }

    private static Array decodeBool(DType dtype, ScalarValue scalar, long n) {
        boolean value = scalar.bool_value() != null && scalar.bool_value();
        return new LazyConstantBoolArray(dtype, n, value);
    }

    private static Array decodeString(DecodeContext ctx, ScalarValue scalar, DType dtype, long n) {
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

        return new VarBinArray.OffsetMode(dtype, n, bytesSeg.asReadOnly(), offsetsSeg.asReadOnly(), PType.I32);
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

}
