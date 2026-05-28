package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import java.nio.ByteBuffer;

/// Encoded array node as stored in a Flat layout segment.
/// In-file representation before decoding; mirrors the Go ArrayNode struct.
record ArrayNode(
    EncodingId  encodingId,
    ByteBuffer  metadata,
    ArrayNode[] children,
    int[]       bufferIndices,
    ArrayStats  stats
) {}
