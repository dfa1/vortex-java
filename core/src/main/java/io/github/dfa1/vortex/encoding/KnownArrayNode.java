package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;

import java.nio.ByteBuffer;

/// Array node whose encoding id is well-known to this build (an [EncodingId] enum constant).
record KnownArrayNode(
        EncodingId encodingId,
        ByteBuffer metadata,
        ArrayNode[] children,
        int[] bufferIndices,
        ArrayStats stats
) implements ArrayNode {
}
