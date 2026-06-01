package io.github.dfa1.vortex.encoding;

import java.nio.ByteBuffer;

/// Describes the ArrayNode tree written into a flat segment's FlatBuffer.
/// Mirrors [ArrayNode] for the encode path.
public record EncodeNode(
		EncodingId encodingId,
		ByteBuffer metadata,
		EncodeNode[] children,
		int[] bufferIndices
) {
	public static EncodeNode leaf(EncodingId encodingId, int bufferIndex) {
		return new EncodeNode(encodingId, null, new EncodeNode[0], new int[]{bufferIndex});
	}

	/// Shift all buffer indices in this node and its descendants by {@code offset}.
	public static EncodeNode remapBufferIndices(EncodeNode node, int offset) {
		if (offset == 0) {
			return node;
		}
		int[] oldIdx = node.bufferIndices();
		int[] newIdx = new int[oldIdx.length];
		for (int i = 0; i < oldIdx.length; i++) {
			newIdx[i] = oldIdx[i] + offset;
		}
		EncodeNode[] oldChildren = node.children();
		EncodeNode[] newChildren = new EncodeNode[oldChildren.length];
		for (int i = 0; i < oldChildren.length; i++) {
			newChildren[i] = remapBufferIndices(oldChildren[i], offset);
		}
		return new EncodeNode(node.encodingId(), node.metadata(), newChildren, newIdx);
	}
}
