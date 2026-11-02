package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import io.github.dfa1.vortex.core.proto.ProtoDecimalBytePartsMetadata;


/// Write-only encoder for `vortex.decimal_byte_parts`.
public final class DecimalBytePartsEncodingEncoder implements EncodingEncoder {

    /// This encoding, barred from its own child: the value part is an integer, never a decimal.
    private static final Set<EncodingId> SELF = Set.of(EncodingId.VORTEX_DECIMAL_BYTE_PARTS);

    /// Creates the encoder.
    public DecimalBytePartsEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DECIMAL_BYTE_PARTS;
    }

    @Override
    public boolean accepts(DType dtype) {
        // zero low parts: the whole unscaled value lives in one i64, so precision 18 is the ceiling
        return dtype instanceof DType.Decimal d && d.precision() <= 18;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.Decimal d = (DType.Decimal) dtype;
        long[] longs = unscaled(data);
        PType mspType = narrowestSigned(longs);
        DType mspDtype = new DType.Primitive(mspType, d.nullable());
        Object msp = PrimitiveArrays.fromLongsArray(longs, mspType, EncodingId.VORTEX_DECIMAL_BYTE_PARTS);
        EncodeResult mspResult = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE).encode(mspDtype, msp, ctx);

        EncodeNode mspNode = EncodeNode.remapBufferIndices(mspResult.rootNode(), 0);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DECIMAL_BYTE_PARTS, metadata(mspType), new EncodeNode[]{mspNode}, new int[]{});
        // The metadata declares zero low parts, so each most-significant part is the whole unscaled
        // value and bounds it exactly. A build that starts emitting low parts must fold them in here
        // rather than keep reporting the msp alone.
        return new EncodeResult(root, mspResult.encodedBuffers(), null, null)
                .withStats(ZoneMapStats.ofDecimal(longs));
    }

    /// The value child goes to the cascade instead of staying a plain `vortex.primitive`, as Rust's
    /// `DecimalScheme` narrows the decimal (`narrowed_decimal`) and compresses its most significant
    /// part with `compress_child` (#644): frame of reference and bit-packing take TPC-H's
    /// `DECIMAL(15,2)` columns to a sixth of their i64 size or less.
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        DType.Decimal d = (DType.Decimal) dtype;
        long[] longs = unscaled(data);
        if (longs.length < 2) {
            // Rust's try_downcast leaves an array with no or one element un-narrowed, so it stays a
            // canonical vortex.decimal; a zone-stats table's single row takes that path
            return CascadeStep.notApplicable();
        }
        PType mspType = narrowestSigned(longs);
        Object msp = PrimitiveArrays.fromLongsArray(longs, mspType, EncodingId.VORTEX_DECIMAL_BYTE_PARTS);
        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_DECIMAL_BYTE_PARTS, metadata(mspType), new EncodeNode[1], new int[0]);
        ChildSlot child = new ChildSlot(new DType.Primitive(mspType, d.nullable()), msp, 0, SELF);
        return new CascadeStep(partialRoot, List.of(), List.of(child), null, null, true)
                .withStats(ZoneMapStats.ofDecimal(longs));
    }

    private static long[] unscaled(Object data) {
        return switch (data) {
            case long[] l -> l;
            case BigDecimal[] values -> unscaledLongs(values);
            default -> throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS,
                    "expected BigDecimal[] or long[], got " + data.getClass().getSimpleName());
        };
    }

    private static MemorySegment metadata(PType mspType) {
        ProtoDecimalBytePartsMetadata proto = new ProtoDecimalBytePartsMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(mspType.ordinal()), 0);
        return MemorySegment.ofArray(proto.encode());
    }

    /// The narrowest signed integer type holding every value, as Rust's `narrowed_decimal` picks among
    /// `i8`, `i16`, `i32` and `i64` (never an unsigned one: a decimal's parts are signed).
    static PType narrowestSigned(long[] values) {
        long min = 0;
        long max = 0;
        for (long v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min >= Byte.MIN_VALUE && max <= Byte.MAX_VALUE) {
            return PType.I8;
        }
        if (min >= Short.MIN_VALUE && max <= Short.MAX_VALUE) {
            return PType.I16;
        }
        if (min >= Integer.MIN_VALUE && max <= Integer.MAX_VALUE) {
            return PType.I32;
        }
        return PType.I64;
    }

    /// Unscaled values as longs; a `null` (masked-out row) becomes 0, never read.
    private static long[] unscaledLongs(BigDecimal[] values) {
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                out[i] = values[i].unscaledValue().longValueExact();
            }
        }
        return out;
    }
}
