package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.DecimalBytePartsMetadata;

import java.nio.ByteBuffer;
import java.util.List;

/// Write-only encoder for {@code vortex.decimal_byte_parts}.
public final class DecimalBytePartsEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public DecimalBytePartsEncodingEncoder() {
    }

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

        DecimalBytePartsMetadata proto = new DecimalBytePartsMetadata(
                io.github.dfa1.vortex.proto.PType.fromValue(PType.I64.ordinal()),
                0);
        ByteBuffer metaBuf = ByteBuffer.wrap(proto.encode());

        EncodeNode mspNode = EncodeNode.remapBufferIndices(mspResult.rootNode(), 0);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DECIMAL_BYTE_PARTS, metaBuf, new EncodeNode[]{mspNode}, new int[]{});
        return new EncodeResult(root, List.copyOf(mspResult.buffers()), null, null);
    }
}
