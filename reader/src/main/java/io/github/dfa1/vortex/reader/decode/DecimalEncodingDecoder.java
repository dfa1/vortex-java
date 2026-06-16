package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.DecimalMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for `vortex.decimal`.
public final class DecimalEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public DecimalEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DECIMAL;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Decimal;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer meta = ctx.metadata();
        if (meta == null || meta.remaining() == 0) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL, "missing metadata");
        }
        DecimalMetadata decoded;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(meta.duplicate());
            decoded = DecimalMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL, "invalid metadata: " + e.getMessage());
        }
        int valuesType = decoded.values_type();
        int byteWidth = decimalTypeByteWidth(valuesType);
        MemorySegment buffer = ctx.buffer(0);
        long expected = ctx.rowCount() * byteWidth;
        if (buffer.byteSize() < expected) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "buffer too small: expected %d bytes, got %d".formatted(expected, buffer.byteSize()));
        }
        return new LazyDecimalArray(ctx.dtype(), ctx.rowCount(), buffer, byteWidth);
    }

    private static int decimalTypeByteWidth(int valuesType) {
        return switch (valuesType) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            case 4 -> 16;
            case 5 -> 32;
            default -> throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "unknown DecimalType value: " + valuesType);
        };
    }
}
