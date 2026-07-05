package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.core.model.EncodingId;

/// Read-only decoder for `vortex.fixed_size_list`.
public final class FixedSizeListEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FIXED_SIZE_LIST;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.FixedSizeList fsl)) {
            throw new VortexException(EncodingId.VORTEX_FIXED_SIZE_LIST,
                    "expected DType.FixedSizeList, got " + ctx.dtype());
        }

        int nchildren = ctx.node().children().length;
        if (nchildren < 1 || nchildren > 2) {
            throw new VortexException(EncodingId.VORTEX_FIXED_SIZE_LIST,
                    "expected 1 or 2 children, got " + nchildren);
        }

        long outerLen = ctx.rowCount();
        long elemLen = outerLen * fsl.fixedSize();
        DType elementType = fsl.elementType();

        ArrayNode elemNode = ctx.node().children()[0];
        var elemCtx = new DecodeContext(
                elemNode, elementType, elemLen,
                ctx.segmentBuffers(), ctx.registry(), ctx.arena());
        Array elements = ctx.registry().decode(elemCtx);

        return new FixedSizeListArray(fsl, outerLen, elements);
    }
}
