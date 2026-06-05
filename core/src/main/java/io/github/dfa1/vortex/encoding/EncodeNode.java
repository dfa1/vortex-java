package io.github.dfa1.vortex.encoding;

import java.nio.ByteBuffer;

/// Describes the ArrayNode tree written into a flat segment's FlatBuffer.
/// Mirrors [ArrayNode] for the encode path.
///
/// @param encodingId    encoding id for this node
/// @param metadata      optional encoding-specific metadata bytes, or {@code null}
/// @param children      child encode nodes (empty for leaf nodes)
/// @param bufferIndices indices into the flat buffer list for data owned by this node
public record EncodeNode(
		EncodingId encodingId,
		ByteBuffer metadata,
		EncodeNode[] children,
		int[] bufferIndices
) {
	/// Creates a leaf node with a single buffer and no children or metadata.
	///
	/// @param encodingId the encoding identifier for this leaf node
	/// @param bufferIndex index into the flat buffer list for the data buffer
	/// @return a leaf {@link EncodeNode} referencing the given buffer
	public static EncodeNode leaf(EncodingId encodingId, int bufferIndex) {
		return new EncodeNode(encodingId, null, new EncodeNode[0], new int[]{bufferIndex});
	}

	/// Shift all buffer indices in this node and its descendants by {@code offset}.
	///
	/// @param node   the root node whose buffer indices to remap
	/// @param offset the value to add to every buffer index in the subtree
	/// @return a new {@link EncodeNode} tree with all buffer indices shifted by {@code offset}
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
