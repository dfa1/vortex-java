package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;

import java.nio.ByteBuffer;

/// Encoded array node as stored in a Flat layout segment.
/// In-file representation before decoding; mirrors the Go ArrayNode struct.
///
/// Sealed: a node is either [KnownArrayNode] (id resolves to an [EncodingId]) or
/// [UnknownArrayNode] (id is an arbitrary string only meaningful for
/// [Registry#allowUnknown()] passthrough decode).
sealed interface ArrayNode permits KnownArrayNode, UnknownArrayNode {
    /// Short factory for the common case: a node whose encoding id is well-known.
    /// Mostly used by tests and helper code that converts an [EncodeNode] tree back into
    /// an [ArrayNode] tree.
    static ArrayNode of(EncodingId encodingId, ByteBuffer metadata, ArrayNode[] children,
            int[] bufferIndices, ArrayStats stats) {
        return new KnownArrayNode(encodingId, metadata, children, bufferIndices, stats);
    }

    ByteBuffer metadata();

    ArrayNode[] children();

    int[] bufferIndices();

    ArrayStats stats();
}
