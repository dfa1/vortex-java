package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.ByteBuffer;

/// Decoding context passed to each [Encoding].
///
/// Buffers are `MemorySegment` slices materialized from the file's segment table;
/// children are decoded recursively via [#decodeChild(int)].
/// The arena is scoped to one chunk epoch — all decode output allocated from it is
/// valid until the next chunk is opened.
///
/// @param node           the array node describing this encoding's tree structure
/// @param dtype          logical type expected for the decoded array
/// @param rowCount       number of logical rows to decode
/// @param segmentBuffers all segment buffers for the current flat segment, indexed by segment position
/// @param registry       encoding registry used for recursive child decoding
/// @param arena          allocator for decode output; lifetime matches the current chunk epoch
public record DecodeContext(
		ArrayNode node,
		DType dtype,
		long rowCount,
		MemorySegment[] segmentBuffers,
		EncodingRegistry registry,
		SegmentAllocator arena
) {
	/// Recursively decode child `i` using the same segment buffers, registry and arena.
	///
	/// @param i zero-based child index within this node's children array
	/// @return the decoded {@link Array} for child {@code i}
	public Array decodeChild(int i) {
		ArrayNode child = node.children()[i];
		var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
		return registry.decode(childCtx);
	}

	/// Return the buffer at position `i` in this node's bufferIndices.
	///
	/// @param i zero-based index into this node's {@code bufferIndices} array
	/// @return the {@link MemorySegment} for the referenced segment buffer
	public MemorySegment buffer(int i) {
		return segmentBuffers[node.bufferIndices()[i]];
	}

	/// Returns the encoding-specific metadata bytes for this node, or {@code null} if absent.
	///
	/// @return the metadata {@link java.nio.ByteBuffer}, or {@code null}
	public ByteBuffer metadata() {
		return node.metadata();
	}
}
