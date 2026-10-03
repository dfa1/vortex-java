package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDecimalMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.decimal`.
public final class DecimalEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DECIMAL;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment meta = ctx.metadata();
        // Absent or empty metadata is proto3's all-defaults message: values_type 0, i.e. i8. Rust
        // writes exactly that for a precision <= 2 column, since the default field is omitted.
        MemorySegment metaSeg = meta == null ? MemorySegment.NULL : meta;
        ProtoDecimalMetadata decoded;
        try {
            decoded = ProtoDecimalMetadata.decode(metaSeg, 0, metaSeg.byteSize());
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
