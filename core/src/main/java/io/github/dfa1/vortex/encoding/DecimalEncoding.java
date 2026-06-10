package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.proto.DecimalMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/// Decoder for {@code vortex.decimal} — canonical flat decimal storage.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Metadata: {@code DecimalMetadata} — {@code values_type int32} (tag 1):
///       DecimalType I8=0, I16=1, I32=2, I64=3, I128=4, I256=5
///   <li>Buffers: 1 — little-endian fixed-width integers sized per {@code values_type}
///   <li>Children: 0 (non-nullable) or 1 (validity)
/// </ul>
public final class DecimalEncoding implements Encoding {

    /// Creates a new {@code DecimalEncoding} instance.
    public DecimalEncoding() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode((DType.Decimal) dtype, (MemorySegment) data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        static EncodeResult encode(DType.Decimal dtype, MemorySegment data) {
            int valuesType = valuesType(dtype.precision());
            int bw = byteWidth(valuesType);
            if (data.byteSize() % bw != 0) {
                throw new VortexException(EncodingId.VORTEX_DECIMAL,
                        "buffer size %d not multiple of byteWidth %d".formatted(data.byteSize(), bw));
            }
            ByteBuffer metaBuf = ByteBuffer.wrap(new DecimalMetadata(valuesType).encode());
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_DECIMAL, metaBuf, new EncodeNode[0], new int[]{0});
            return new EncodeResult(node, List.of(data), null, null);
        }

        private static int valuesType(byte precision) {
            if (precision <= 2) {
                return 0;
            }
            if (precision <= 4) {
                return 1;
            }
            if (precision <= 9) {
                return 2;
            }
            if (precision <= 18) {
                return 3;
            }
            if (precision <= 38) {
                return 4;
            }
            return 5;
        }

        private static int byteWidth(int valuesType) {
            return switch (valuesType) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 8;
                case 4 -> 16;
                case 5 -> 32;
                default -> throw new VortexException(EncodingId.VORTEX_DECIMAL,
                        "unknown valuesType: " + valuesType);
            };
        }
    }

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
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
            MemorySegment buffer = ctx.buffer(0).unwrapForSubParser("decimal encoding");
            long expected = ctx.rowCount() * byteWidth;
            if (buffer.byteSize() < expected) {
                throw new VortexException(EncodingId.VORTEX_DECIMAL,
                        "buffer too small: expected %d bytes, got %d".formatted(expected, buffer.byteSize()));
            }
            return new GenericArray(ctx.dtype(), ctx.rowCount(), buffer);
        }

        private static int decimalTypeByteWidth(int valuesType) {
            return switch (valuesType) {
                case 0 -> 1;  // I8
                case 1 -> 2;  // I16
                case 2 -> 4;  // I32
                case 3 -> 8;  // I64
                case 4 -> 16; // I128
                case 5 -> 32; // I256
                default -> throw new VortexException(EncodingId.VORTEX_DECIMAL,
                        "unknown DecimalType value: " + valuesType);
            };
        }
    }
}
