package io.github.dfa1.vortex.reader.decode;

import java.nio.ByteBuffer;

/// Array node whose encoding id is not a recognised {@link io.github.dfa1.vortex.encoding.EncodingId}.
/// Produced when a file uses an encoding this build does not know about. Decoded as
/// {@link io.github.dfa1.vortex.reader.array.UnknownArray} when
/// {@link io.github.dfa1.vortex.reader.ReadRegistry#isAllowUnknown()} is set; otherwise the decode call throws.
///
/// @param rawEncodingId the raw encoding id string from the file
/// @param metadata      encoding-specific metadata bytes, or {@code null}
/// @param children      child nodes
/// @param bufferIndices segment buffer indices
public record UnknownArrayNode(
        String rawEncodingId,
        ByteBuffer metadata,
        ArrayNode[] children,
        int[] bufferIndices
) implements ArrayNode {
}
