package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.varbinview` (Apache Arrow StringView/BinaryView).
public final class VarBinViewEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARBINVIEW;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary)) {
            throw new VortexException(EncodingId.VORTEX_VARBINVIEW,
                    "expected Utf8/Binary dtype, got " + ctx.dtype());
        }

        int numBufs = ctx.node().bufferIndices().length;
        if (numBufs < 1) {
            throw new VortexException(EncodingId.VORTEX_VARBINVIEW,
                    "expected at least 1 buffer (views), got 0");
        }

        // Lazy path: keep views + data buffers as MemorySegment slices; per-row
        // accessors resolve on demand via VarBinArray.ViewMode. No copy, no concat,
        // no flat byte buffer allocation.
        MemorySegment viewsBuf = ctx.buffer(numBufs - 1);
        MemorySegment[] dataBufs = new MemorySegment[numBufs - 1];
        for (int i = 0; i < dataBufs.length; i++) {
            dataBufs[i] = ctx.buffer(i);
        }
        return new VarBinArray.ViewMode(ctx.dtype(), ctx.rowCount(), viewsBuf, dataBufs);
    }
}
