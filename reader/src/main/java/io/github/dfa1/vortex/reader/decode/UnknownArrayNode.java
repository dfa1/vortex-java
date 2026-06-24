package io.github.dfa1.vortex.reader.decode;

import java.lang.foreign.MemorySegment;

/// Array node whose encoding id is not a recognised [io.github.dfa1.vortex.core.model.EncodingId].
/// Produced when a file uses an encoding this build does not know about. Decoded as
/// [io.github.dfa1.vortex.reader.array.UnknownArray] when
/// [io.github.dfa1.vortex.reader.ReadRegistry#isAllowUnknown()] is set; otherwise the decode call throws.
///
/// @param rawEncodingId the raw encoding id string from the file
/// @param metadata      encoding-specific metadata bytes, or `null`
/// @param children      child nodes
/// @param bufferIndices segment buffer indices
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record UnknownArrayNode(
        String rawEncodingId,
        MemorySegment metadata,
        ArrayNode[] children,
        int[] bufferIndices
) implements ArrayNode {
}
