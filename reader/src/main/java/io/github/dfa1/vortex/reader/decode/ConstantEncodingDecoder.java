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
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

        MemorySegment outSeg = ctx.arena().allocate(elemBytes);
        ByteBuffer out = outSeg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        writeRaw(out, ptype, rawBits);

        MemorySegment ro = outSeg.asReadOnly();
        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(ctx.dtype(), n, ro);
            case I32, U32 -> new MaterializedIntArray(ctx.dtype(), n, ro);
            case F64 -> new MaterializedDoubleArray(ctx.dtype(), n, ro);
            case F32 -> new MaterializedFloatArray(ctx.dtype(), n, ro);
            case I16, U16 -> new MaterializedShortArray(ctx.dtype(), n, ro);
            case I8, U8 -> new MaterializedByteArray(ctx.dtype(), n, ro);
            default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
        };
    }

    private static Array decodeDecimal(DecodeContext ctx, ScalarValue scalar, long n) {
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
        return new MaterializedBoolArray(ctx.dtype(), n, seg.asReadOnly());
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
