package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyDecimalBytePartsArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.DecimalBytePartsMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Read-only decoder for `vortex.decimal_byte_parts`.
public final class DecimalBytePartsEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public DecimalBytePartsEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DECIMAL_BYTE_PARTS;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer meta = ctx.metadata();
        if (meta == null || meta.remaining() == 0) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS, "missing metadata");
        }
        DecimalBytePartsMetadata decoded;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(meta.duplicate());
            decoded = DecimalBytePartsMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS, "invalid metadata: " + e.getMessage());
        }

        int lowerPartCount = decoded.lower_part_count();
        if (lowerPartCount != 0) {
            throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS,
                    "lower_part_count > 0 not supported, got " + lowerPartCount);
        }

        PType mspPtype = PType.fromOrdinal(decoded.zeroth_child_ptype().value());
        boolean nullable = ctx.dtype().nullable();
        DType mspDtype = new DType.Primitive(mspPtype, nullable);
        ArrayNode mspNode = ctx.node().children()[0];
        DecodeContext mspCtx = new DecodeContext(
                mspNode, mspDtype, ctx.rowCount(),
                ctx.segmentBuffers(), ctx.registry(), ctx.arena());
        Array mspArray = ctx.registry().decode(mspCtx);
        return new LazyDecimalBytePartsArray(ctx.dtype(), ctx.rowCount(), mspArray);
    }
}
