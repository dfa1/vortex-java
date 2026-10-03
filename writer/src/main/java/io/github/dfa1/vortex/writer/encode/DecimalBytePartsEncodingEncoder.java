package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import io.github.dfa1.vortex.core.proto.ProtoDecimalBytePartsMetadata;


/// Write-only encoder for `vortex.decimal_byte_parts`.
public final class DecimalBytePartsEncodingEncoder implements EncodingEncoder {

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
        long[] longs = switch (data) {
            case long[] l -> l;
            case BigDecimal[] values -> unscaledLongs(values);
            default -> throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS,
                    "expected BigDecimal[] or long[], got " + data.getClass().getSimpleName());
        };
        DType mspDtype = new DType.Primitive(PType.I64, d.nullable());
        EncodeResult mspResult = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE).encode(mspDtype, longs, ctx);

        ProtoDecimalBytePartsMetadata proto = new ProtoDecimalBytePartsMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal()),
                0);
        MemorySegment metaBuf = MemorySegment.ofArray(proto.encode());

        EncodeNode mspNode = EncodeNode.remapBufferIndices(mspResult.rootNode(), 0);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DECIMAL_BYTE_PARTS, metaBuf, new EncodeNode[]{mspNode}, new int[]{});
        // The metadata above declares zero low parts, so each i64 most-significant part is the
        // whole unscaled value and bounds it exactly. A build that starts emitting low parts must
        // fold them in here rather than keep reporting the msp alone.
        return new EncodeResult(root, mspResult.encodedBuffers(), null, null)
                .withStats(ZoneMapStats.ofDecimal(longs));
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
