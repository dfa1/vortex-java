package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Read-only decoder for {@code vortex.varbinview} (Apache Arrow StringView/BinaryView).
public final class VarBinViewEncodingDecoder implements EncodingDecoder {

    private static final int MAX_INLINED_SIZE = 12;
    private static final int VIEW_SIZE = 16;

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public VarBinViewEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARBINVIEW;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
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

        MemorySegment viewsBuf = ctx.buffer(numBufs - 1);
        MemorySegment[] dataBufs = new MemorySegment[numBufs - 1];
        for (int i = 0; i < dataBufs.length; i++) {
            dataBufs[i] = ctx.buffer(i);
        }

        long n = ctx.rowCount();

        long totalBytes = 0;
        for (long i = 0; i < n; i++) {
            long size = Integer.toUnsignedLong(viewsBuf.get(PTypeIO.LE_INT, i * VIEW_SIZE));
            totalBytes += size;
        }

        MemorySegment outBytes = ctx.arena().allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment outOffsets = ctx.arena().allocate((n + 1) * Long.BYTES, Long.BYTES);

        long bytePos = 0;
        outOffsets.setAtIndex(PTypeIO.LE_LONG, 0, 0L);
        for (long i = 0; i < n; i++) {
            long viewOff = i * VIEW_SIZE;
            long size = Integer.toUnsignedLong(viewsBuf.get(PTypeIO.LE_INT, viewOff));
            if (size <= MAX_INLINED_SIZE) {
                MemorySegment.copy(viewsBuf, viewOff + 4, outBytes, bytePos, size);
            } else {
                int bufferIndex = viewsBuf.get(PTypeIO.LE_INT, viewOff + 8);
                long srcOffset = Integer.toUnsignedLong(viewsBuf.get(PTypeIO.LE_INT, viewOff + 12));
                MemorySegment.copy(dataBufs[bufferIndex], srcOffset, outBytes, bytePos, size);
            }
            bytePos += size;
            outOffsets.setAtIndex(PTypeIO.LE_LONG, i + 1, bytePos);
        }

        return new VarBinArray(ctx.dtype(), n, outBytes.asReadOnly(), outOffsets, PType.I64);
    }
}
