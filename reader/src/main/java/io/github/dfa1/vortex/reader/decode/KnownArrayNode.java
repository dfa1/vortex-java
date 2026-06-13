package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.encoding.EncodingId;

import java.nio.ByteBuffer;

/// Array node whose encoding id is well-known to this build (an {@link EncodingId} enum constant).
///
/// @param encodingId    well-known encoding id
/// @param metadata      encoding-specific metadata bytes, or {@code null}
/// @param children      child nodes
/// @param bufferIndices segment buffer indices
public record KnownArrayNode(
        EncodingId encodingId,
        ByteBuffer metadata,
        ArrayNode[] children,
        int[] bufferIndices
) implements ArrayNode {
}
