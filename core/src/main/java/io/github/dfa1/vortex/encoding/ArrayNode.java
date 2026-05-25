package io.github.dfa1.vortex.encoding;

/// Encoded array node as stored in a Flat layout segment.
/// In-file representation before decoding; mirrors the Go ArrayNode struct.
public record ArrayNode(
    String      encodingId,
    byte[]      metadata,
    ArrayNode[] children,
    int[]       bufferIndices,
    ArrayStats  stats
) {}
