package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.encoding.EncodingId;

import java.nio.ByteBuffer;

/// Encoded array node as stored in a Flat layout segment.
/// In-file representation before decoding; mirrors the Go ArrayNode struct.
///
/// Sealed: a node is either [KnownArrayNode] (id resolves to an [EncodingId]) or
/// [UnknownArrayNode] (id is an arbitrary string only meaningful for
/// [io.github.dfa1.vortex.reader.ReadRegistry#isAllowUnknown()] passthrough decode).
public sealed interface ArrayNode permits KnownArrayNode, UnknownArrayNode {

    /// Short factory for the common case: a node whose encoding id is well-known.
    /// Mostly used by tests and helper code that converts an `EncodeNode` tree back into
    /// an `ArrayNode` tree.
    ///
    /// @param encodingId    the well-known encoding identifier
    /// @param metadata      encoding-specific metadata bytes, or `null`
    /// @param children      child nodes
    /// @param bufferIndices segment buffer indices for this node
    /// @return a [KnownArrayNode] with the given fields
    static ArrayNode of(EncodingId encodingId, ByteBuffer metadata, ArrayNode[] children,
            int[] bufferIndices) {
        return new KnownArrayNode(encodingId, metadata, children, bufferIndices);
    }

    /// @return encoding-specific metadata bytes, or `null`
    ByteBuffer metadata();

    /// @return child nodes
    ArrayNode[] children();

    /// @return segment buffer indices for this node
    int[] bufferIndices();
}
