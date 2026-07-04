package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;

/// Encoded array node as stored in a Flat layout segment — the in-file representation before
/// decoding.
///
/// The encoding id is carried as the raw string from the wire, because encoding ids ARE strings in
/// the format (`"vortex.flat"`, `"fastlanes.bitpacked"`, …). Whether an id is decodable is the
/// [io.github.dfa1.vortex.reader.ReadRegistry]'s question at decode time, not a property of the
/// node: an id no registered decoder claims either decodes as a passthrough
/// [io.github.dfa1.vortex.reader.array.UnknownArray] (when
/// [io.github.dfa1.vortex.reader.ReadRegistry#isAllowUnknown()] is set) or fails the decode.
///
/// @param encodingId    the raw encoding id string from the file
/// @param metadata      encoding-specific metadata bytes, or `null`
/// @param children      child nodes
/// @param bufferIndices segment buffer indices for this node
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ArrayNode(
        String encodingId,
        MemorySegment metadata,
        ArrayNode[] children,
        int[] bufferIndices
) {

    /// Short factory for the common case: a node whose encoding id is well-known. Mostly used by
    /// tests and helper code that converts an `EncodeNode` tree back into an `ArrayNode` tree.
    ///
    /// @param encodingId    the well-known encoding identifier
    /// @param metadata      encoding-specific metadata bytes, or `null`
    /// @param children      child nodes
    /// @param bufferIndices segment buffer indices for this node
    /// @return an [ArrayNode] carrying the id's wire string
    public static ArrayNode of(EncodingId encodingId, MemorySegment metadata, ArrayNode[] children,
            int[] bufferIndices) {
        return new ArrayNode(encodingId.id(), metadata, children, bufferIndices);
    }
}
