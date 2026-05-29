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
/// valid until the next [io.github.dfa1.vortex.scan.ScanIterator#hasNext()] call.
public record DecodeContext(
		ArrayNode node,
		DType dtype,
		long rowCount,
		MemorySegment[] segmentBuffers,
		EncodingRegistry registry,
		SegmentAllocator arena
) {
	/// Recursively decode child `i` using the same segment buffers, registry and arena.
	public Array decodeChild(int i) {
		ArrayNode child = node.children()[i];
		var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
		return registry.decode(childCtx);
	}

	/// Return the buffer at position `i` in this node's bufferIndices.
	public MemorySegment buffer(int i) {
		return segmentBuffers[node.bufferIndices()[i]];
	}

	public ByteBuffer metadata() {
		return node.metadata();
	}
}
