package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDecimalMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;

/// Read-only decoder for `vortex.decimal`, with Rust 0.95's read rules (`StoredDecimal` and
/// `WidenArray::from_stored_decimal`): one buffer of exactly one value per row, aligned to the
/// value width, a validity child only on a nullable column, and, when the stored width is not the
/// canonical width of the precision, every valid value within the precision. A canonical-width
/// node is trusted without a scan.
public final class DecimalEncodingDecoder implements EncodingDecoder {

    /// Creates the decoder.
    public DecimalEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DECIMAL;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Decimal decimal)) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL, "requires a decimal dtype, got " + ctx.dtype());
        }
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
        int byteWidth = decimalTypeByteWidth(decoded.values_type());
        int buffers = ctx.node().bufferIndices().length;
        if (buffers != 1) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL, "expected 1 buffer, got " + buffers);
        }
        MemorySegment buffer = ctx.buffer(0);
        // divided, not multiplied: a crafted row count must not overflow past the check
        if (buffer.byteSize() % byteWidth != 0 || buffer.byteSize() / byteWidth != ctx.rowCount()) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "buffer must hold exactly %d values of %d bytes, got %d bytes"
                            .formatted(ctx.rowCount(), byteWidth, buffer.byteSize()));
        }
        // Rust's i256 is two 128-bit halves, so no decimal width needs more than 16
        int alignment = Math.min(byteWidth, 16);
        if (!ctx.bufferAligned(0, alignment)) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL,
                    "buffer of %d-byte values must declare %d-byte alignment".formatted(byteWidth, alignment));
        }
        // A nullable column carries its validity as an optional single child (Rust: `children.get(0, ..)`),
        // not under a vortex.masked wrapper. Ignoring it read every null row back as zero.
        int children = ctx.node().children().length;
        if (children > 1) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL, "expected 0 or 1 children, got " + children);
        }
        if (children == 1 && !decimal.nullable()) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL, "a validity child requires a nullable dtype");
        }
        BoolArray validity = null;
        if (children == 1) {
            validity = MaskedArray.requireBoolArray(ctx.decodeChild(0, DTypes.BOOL, ctx.rowCount()),
                    EncodingId.VORTEX_DECIMAL, "validity child");
        }
        if (byteWidth != decimal.byteWidth()) {
            requireWithinPrecision(buffer, byteWidth, ctx.rowCount(), validity, decimal.precision());
        }
        if (validity == null) {
            return new LazyDecimalArray(decimal, ctx.rowCount(), buffer, byteWidth);
        }
        return new MaskedArray(new LazyDecimalArray(decimal.withNullable(false), ctx.rowCount(), buffer, byteWidth),
                validity);
    }

    /// A legacy node, stored at another width than its precision's, holds only valid values that
    /// fit the precision, which Rust checks while choosing the array it reads them as
    /// (`smallest_decimal_dtype`). Null rows may hold anything.
    private static void requireWithinPrecision(MemorySegment buffer, int byteWidth, long rows, BoolArray validity,
                                               int precision) {
        // a value of 8 bytes or fewer is under 10^19, and one of 16 bytes under 10^39
        if (byteWidth <= 8 && precision >= 19 || byteWidth == 16 && precision >= 39) {
            return;
        }
        DType.Decimal unscaled = DType.Decimal.of(precision, 0, false);
        var values = new LazyDecimalArray(unscaled, rows, buffer, byteWidth);
        BigInteger max = BigInteger.TEN.pow(precision).subtract(BigInteger.ONE);
        long maxLong = byteWidth <= 8 ? max.longValueExact() : 0;
        for (long i = 0; i < rows; i++) {
            if (validity != null && !validity.getBoolean(i)) {
                continue;
            }
            boolean fits;
            if (byteWidth <= 8) {
                long v = values.getUnscaledLong(i);
                fits = v >= -maxLong && v <= maxLong;
            } else {
                fits = values.getDecimal(i).unscaledValue().abs().compareTo(max) <= 0;
            }
            if (!fits) {
                throw new VortexException(EncodingId.VORTEX_DECIMAL,
                        "valid value %s at row %d does not fit precision %d"
                                .formatted(values.getDecimal(i), i, precision));
            }
        }
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
