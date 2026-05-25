package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/**
 * Decoding context passed to each {@link Decoder}.
 *
 * <p>Buffers are MemorySegment slices materialized from the file's segment table;
 * children are decoded recursively via {@link #decodeChild}.
 */
public record DecodeContext(
    ArrayNode        node,
    DType            dtype,
    long             rowCount,
    MemorySegment[]  segmentBuffers,
    DecoderRegistry  registry
) {
    /** Recursively decode child {@code i} using the same segment buffers and registry. */
    public Array decodeChild(int i) throws IOException {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry);
        return registry.decode(childCtx);
    }

    /** Return the buffer at position {@code i} in this node's bufferIndices. */
    public MemorySegment buffer(int i) {
        return segmentBuffers[node.bufferIndices()[i]];
    }

    public byte[] metadata() { return node.metadata(); }
}
