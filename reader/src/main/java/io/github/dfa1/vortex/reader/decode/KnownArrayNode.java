package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;

/// Array node whose encoding id is well-known to this build (an [EncodingId] enum constant).
///
/// @param encodingId    well-known encoding id
/// @param metadata      encoding-specific metadata bytes, or `null`
/// @param children      child nodes
/// @param bufferIndices segment buffer indices
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record KnownArrayNode(
        EncodingId encodingId,
        MemorySegment metadata,
        ArrayNode[] children,
        int[] bufferIndices
) implements ArrayNode {
}
