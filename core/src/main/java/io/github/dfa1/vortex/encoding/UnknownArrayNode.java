package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;

import java.nio.ByteBuffer;

/// Array node whose encoding id is not a recognised [EncodingId]. Produced when a file uses
/// an encoding this build does not know about. Decoded as
/// [io.github.dfa1.vortex.core.array.UnknownArray] when
/// [EncodingRegistry#allowUnknown()] is set; otherwise the decode call throws.
record UnknownArrayNode(
		String rawEncodingId,
		ByteBuffer metadata,
		ArrayNode[] children,
		int[] bufferIndices,
		ArrayStats stats
) implements ArrayNode {
}
