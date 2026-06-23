package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;



import io.github.dfa1.vortex.encoding.EncodingId;





import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.chunked`.
public final class ChunkedEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public ChunkedEncodingEncoder() {
    }

    private static final List<EncodingEncoder> FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(), new BoolEncodingEncoder(),
            new NullEncodingEncoder(), new ByteBoolEncodingEncoder(), new StructEncodingEncoder());

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_CHUNKED;
    }

    @Override
    public boolean accepts(DType dtype) {
        // Carrier-dispatched, not dtype-dispatched: encode() requires a ChunkedData wrapper, which
        // the dtype-based write path never produces. Returning false keeps this encoder out of
        // first-match selection (VortexWriter.findEncoder and the cascade), where it would
        // otherwise be handed a raw primitive array and throw ClassCastException. It stays usable
        // by explicit construction / keyed lookup for callers that already hold ChunkedData.
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        ChunkedData cd = (ChunkedData) data;
        List<Object> chunks = cd.chunks();
        long[] chunkLengths = cd.chunkLengths();
        int nchunks = chunks.size();
        if (nchunks == 0) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED, "at least one chunk required");
        }

        long[] offsets = new long[nchunks + 1];
        offsets[0] = 0;
        for (int i = 0; i < nchunks; i++) {
            offsets[i + 1] = offsets[i] + chunkLengths[i];
        }

        DType u64 = DType.U64;
        EncodeResult offsetsResult = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE).encode(u64, offsets, ctx);

        List<MemorySegment> allBuffers = new ArrayList<>(offsetsResult.buffers());
        EncodeNode[] children = new EncodeNode[nchunks + 1];
        children[0] = offsetsResult.rootNode();

        EncodingEncoder inner = findEncoding(dtype);
        for (int i = 0; i < nchunks; i++) {
            EncodeResult chunkResult = inner.encode(dtype, chunks.get(i), ctx);
            int bufOffset = allBuffers.size();
            children[i + 1] = EncodeNode.remapBufferIndices(chunkResult.rootNode(), bufOffset);
            allBuffers.addAll(chunkResult.buffers());
        }

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_CHUNKED,
                ByteBuffer.wrap(new byte[0]),
                children,
                new int[]{});
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    private static EncodingEncoder findEncoding(DType dtype) {
        for (EncodingEncoder enc : FALLBACK) {
            if (enc.accepts(dtype)) {
                return enc;
            }
        }
        throw new UnsupportedOperationException("no fallback encoding for dtype: " + dtype);
    }
}
