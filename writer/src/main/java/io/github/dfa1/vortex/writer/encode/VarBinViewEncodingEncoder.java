package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.varbinview`.
public final class VarBinViewEncodingEncoder implements EncodingEncoder {

    private static final int MAX_INLINED_SIZE = 12;
    private static final int VIEW_SIZE = 16;
    /// Largest data buffer: a view's offset into it stays a non-negative `int`, Rust's
    /// `MAX_BUFFER_LEN` (`i32::MAX`).
    private static final long MAX_DATA_BUFFER_BYTES = Integer.MAX_VALUE;

    private final long maxDataBufferBytes;

    /// Creates the encoder.
    public VarBinViewEncodingEncoder() {
        this(MAX_DATA_BUFFER_BYTES);
    }

    /// Creates the encoder with a smaller data buffer cap, so tests reach the split without 2 GiB.
    ///
    /// @param maxDataBufferBytes the largest data buffer
    VarBinViewEncodingEncoder(long maxDataBufferBytes) {
        this.maxDataBufferBytes = maxDataBufferBytes;
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        byte[][] bytes = VarBinBytes.toByteArrays(data);
        int n = bytes.length;

        // Out-of-line rows fill data buffers in order; a buffer is closed before a row would take
        // it past 2 GiB, the most a view's offset reaches here, so a chunk of any size fits (#633)
        List<Long> bufferSizes = new ArrayList<>();
        long current = 0;
        for (int i = 0; i < n; i++) {
            int len = bytes[i].length;
            if (len > MAX_INLINED_SIZE) {
                if (current > 0 && current + len > maxDataBufferBytes) {
                    bufferSizes.add(current);
                    current = 0;
                }
                current += len;
            }
        }
        if (current > 0) {
            bufferSizes.add(current);
        }

        Arena arena = ctx.arena();
        MemorySegment[] dataBufs = new MemorySegment[bufferSizes.size()];
        for (int b = 0; b < dataBufs.length; b++) {
            dataBufs[b] = arena.allocate(bufferSizes.get(b));
        }
        MemorySegment viewsBuf = arena.allocate(n > 0 ? (long) n * VIEW_SIZE : 1);

        int bufferIndex = 0;
        long dataOffset = 0;
        for (int i = 0; i < n; i++) {
            byte[] b = bytes[i];
            long viewOff = (long) i * VIEW_SIZE;
            viewsBuf.set(VortexFormat.LE_INT, viewOff, b.length);
            if (b.length <= MAX_INLINED_SIZE) {
                MemorySegment.copy(MemorySegment.ofArray(b), 0, viewsBuf, viewOff + 4, b.length);
            } else {
                if (dataOffset + b.length > dataBufs[bufferIndex].byteSize()) {
                    bufferIndex++;
                    dataOffset = 0;
                }
                MemorySegment.copy(MemorySegment.ofArray(b), 0, viewsBuf, viewOff + 4, 4);
                viewsBuf.set(VortexFormat.LE_INT, viewOff + 8, bufferIndex);
                viewsBuf.set(VortexFormat.LE_INT, viewOff + 12, (int) dataOffset);
                MemorySegment.copy(MemorySegment.ofArray(b), 0, dataBufs[bufferIndex], dataOffset, b.length);
                dataOffset += b.length;
            }
        }

        // Views are Rust's `#[repr(C, align(16))] BinaryView`; the data buffers are plain bytes,
        // listed before the views.
        List<EncodedBuffer> buffers = new ArrayList<>(dataBufs.length + 1);
        for (MemorySegment dataBuf : dataBufs) {
            buffers.add(EncodedBuffer.bytes(dataBuf));
        }
        buffers.add(new EncodedBuffer(viewsBuf, VIEW_SIZE));
        int[] bufIndices = new int[buffers.size()];
        for (int b = 0; b < bufIndices.length; b++) {
            bufIndices[b] = b;
        }

        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARBINVIEW, null, new EncodeNode[0], bufIndices);
        // Same lexicographic bounds vortex.varbin reports for the same column: which of the two
        // string layouts the compressor picks must not decide whether the column can be pruned.
        return new EncodeResult(root, List.copyOf(buffers), null, null).withStats(ZoneMapStats.of(dtype, data));
    }
}
