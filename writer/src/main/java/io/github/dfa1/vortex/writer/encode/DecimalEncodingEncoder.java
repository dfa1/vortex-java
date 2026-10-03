package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDecimalMetadata;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/// Write-only encoder for `vortex.decimal`.
public final class DecimalEncodingEncoder implements EncodingEncoder {

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
        DType.Decimal d = (DType.Decimal) dtype;
        int valuesType = valuesType(d.precision());
        int bw = byteWidth(valuesType);
        MemorySegment seg = switch (data) {
            case MemorySegment s -> s;
            case BigDecimal[] values -> pack(values, bw, ctx.arena());
            default -> throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "expected BigDecimal[] or MemorySegment, got " + data.getClass().getSimpleName());
        };
        if (seg.byteSize() % bw != 0) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "buffer size %d not multiple of byteWidth %d".formatted(seg.byteSize(), bw));
        }
        MemorySegment metaBuf = MemorySegment.ofArray(new ProtoDecimalMetadata(valuesType).encode());
        EncodeNode node = new EncodeNode(EncodingId.VORTEX_DECIMAL, metaBuf, new EncodeNode[0], new int[]{0});
        // Rust's i256 is two 128-bit halves, so no decimal width aligns past 16 bytes.
        return new EncodeResult(node, List.of(new EncodedBuffer(seg, Math.min(bw, 16))), null, null).withStats(ZoneMapStats.ofDecimal(seg, bw));
    }

    /// Storage width in bytes of an unscaled value at `precision` (1, 2, 4, 8, 16 or 32), as in Rust.
    ///
    /// @param precision the decimal precision
    /// @return bytes per value
    static int storageWidth(byte precision) {
        return byteWidth(valuesType(precision));
    }

    /// Packs the unscaled values little-endian, two's complement, `width` bytes each. A `null`
    /// (a masked-out row) packs as 0: the enclosing validity marks it null, so it is never read.
    /// The caller already rescaled every value to the column scale and checked its precision.
    ///
    /// @param values    unscaled-ready decimals, `null` for masked rows
    /// @param width     bytes per value
    /// @param allocator where the packed buffer is allocated
    /// @return the packed buffer, `values.length * width` bytes
    static MemorySegment pack(BigDecimal[] values, int width, SegmentAllocator allocator) {
        MemorySegment out = allocator.allocate((long) values.length * width, Math.min(width, 16));
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            BigInteger unscaled = values[i].unscaledValue();
            long off = (long) i * width;
            if (width <= 8) {
                long v = unscaled.longValue();
                for (int b = 0; b < width; b++) {
                    out.set(ValueLayout.JAVA_BYTE, off + b, (byte) (v >>> (8 * b)));
                }
            } else {
                // toByteArray is big-endian and minimal: reverse it, then sign-extend to the width
                byte[] be = unscaled.toByteArray();
                byte fill = unscaled.signum() < 0 ? (byte) -1 : 0;
                for (int b = 0; b < width; b++) {
                    out.set(ValueLayout.JAVA_BYTE, off + b, b < be.length ? be[be.length - 1 - b] : fill);
                }
            }
        }
        return out;
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
