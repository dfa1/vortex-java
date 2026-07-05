package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import java.lang.foreign.MemorySegment;
import io.github.dfa1.vortex.core.proto.ProtoDecimalBytePartsMetadata;

import java.util.List;

/// Write-only encoder for `vortex.decimal_byte_parts`.
public final class DecimalBytePartsEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DECIMAL_BYTE_PARTS;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Decimal;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        DType.Decimal d = (DType.Decimal) dtype;
        long[] longs = (long[]) data;
        DType mspDtype = new DType.Primitive(PType.I64, d.nullable());
        EncodeResult mspResult = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE).encode(mspDtype, longs, ctx);

        ProtoDecimalBytePartsMetadata proto = new ProtoDecimalBytePartsMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal()),
                0);
        MemorySegment metaBuf = MemorySegment.ofArray(proto.encode());

        EncodeNode mspNode = EncodeNode.remapBufferIndices(mspResult.rootNode(), 0);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DECIMAL_BYTE_PARTS, metaBuf, new EncodeNode[]{mspNode}, new int[]{});
        return new EncodeResult(root, List.copyOf(mspResult.buffers()), null, null);
    }
}
