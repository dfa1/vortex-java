package io.github.dfa1.vortex.encoding;

import java.nio.ByteBuffer;

/// Describes the ArrayNode tree written into a flat segment's FlatBuffer.
/// Mirrors [ArrayNode] for the encode path.
public record EncodeNode(
		CodecId encodingId,
		ByteBuffer metadata,
		EncodeNode[] children,
		int[] bufferIndices
) {
	public static EncodeNode leaf(CodecId encodingId, int bufferIndex) {
		return new EncodeNode(encodingId, null, new EncodeNode[0], new int[]{bufferIndex});
	}
}
