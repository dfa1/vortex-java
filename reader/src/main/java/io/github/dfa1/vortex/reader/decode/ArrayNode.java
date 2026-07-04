package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;

/// Encoded array node as stored in a Flat layout segment.
/// In-file representation before decoding; mirrors the Go ArrayNode struct.
///
/// Sealed: a node is either [KnownArrayNode] (id resolves to an [EncodingId.WellKnown]) or
/// [UnknownArrayNode] (id is an arbitrary string only meaningful for
/// [io.github.dfa1.vortex.reader.ReadRegistry#isAllowUnknown()] passthrough decode).
public sealed interface ArrayNode permits KnownArrayNode, UnknownArrayNode {

    /// Factory that builds the right node kind for `encodingId`: a [KnownArrayNode] for a
    /// well-known id, or an [UnknownArrayNode] for a [EncodingId.Custom] one.
    /// Mostly used by tests and helper code that converts an `EncodeNode` tree back into
    /// an `ArrayNode` tree.
    ///
    /// @param encodingId    the encoding identifier
    /// @param metadata      encoding-specific metadata bytes, or `null`
    /// @param children      child nodes
    /// @param bufferIndices segment buffer indices for this node
    /// @return a [KnownArrayNode] for a well-known id, else an [UnknownArrayNode]
    static ArrayNode of(EncodingId encodingId, MemorySegment metadata, ArrayNode[] children,
            int[] bufferIndices) {
        return switch (encodingId) {
            case EncodingId.WellKnown wellKnown ->
                    new KnownArrayNode(wellKnown, metadata, children, bufferIndices);
            case EncodingId.Custom custom ->
                    new UnknownArrayNode(custom.id(), metadata, children, bufferIndices);
        };
    }

    /// @return encoding-specific metadata bytes, or `null`
    MemorySegment metadata();

    /// @return child nodes
    ArrayNode[] children();

    /// @return segment buffer indices for this node
    int[] bufferIndices();
}
