package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/// Write-only encoder for `vortex.varbinview`.
public final class VarBinViewEncodingEncoder implements EncodingEncoder {

    private static final int MAX_INLINED_SIZE = 12;
    private static final int VIEW_SIZE = 16;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARBINVIEW;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        byte[][] bytes = VarBinBytes.toByteArrays(data);
        int n = bytes.length;

        int totalDataBytes = 0;
        for (int i = 0; i < n; i++) {
            if (bytes[i].length > MAX_INLINED_SIZE) {
                totalDataBytes += bytes[i].length;
            }
        }

        Arena arena = ctx.arena();
        boolean hasDataBuf = totalDataBytes > 0;
        MemorySegment dataBuf = arena.allocate(hasDataBuf ? totalDataBytes : 1);
        MemorySegment viewsBuf = arena.allocate(n > 0 ? (long) n * VIEW_SIZE : 1);

        int dataOffset = 0;
        for (int i = 0; i < n; i++) {
            byte[] b = bytes[i];
            long viewOff = (long) i * VIEW_SIZE;
            viewsBuf.set(VortexFormat.LE_INT, viewOff, b.length);
            if (b.length <= MAX_INLINED_SIZE) {
                MemorySegment.copy(MemorySegment.ofArray(b), 0, viewsBuf, viewOff + 4, b.length);
            } else {
                MemorySegment.copy(MemorySegment.ofArray(b), 0, viewsBuf, viewOff + 4, 4);
                viewsBuf.set(VortexFormat.LE_INT, viewOff + 8, 0);
                viewsBuf.set(VortexFormat.LE_INT, viewOff + 12, dataOffset);
                MemorySegment.copy(MemorySegment.ofArray(b), 0, dataBuf, dataOffset, b.length);
                dataOffset += b.length;
            }
        }

        int[] bufIndices;
        List<MemorySegment> buffers;
        if (hasDataBuf) {
            bufIndices = new int[]{0, 1};
            buffers = List.of(dataBuf, viewsBuf);
        } else {
            bufIndices = new int[]{0};
            buffers = List.of(viewsBuf);
        }

        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARBINVIEW, null, new EncodeNode[0], bufIndices);
        return new EncodeResult(root, buffers, null, null);
    }
}
