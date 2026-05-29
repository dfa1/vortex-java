package io.github.dfa1.vortex.encoding;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/// Converts an [EncodeResult] into a [DecodeContext] for roundtrip tests.
final class EncodeTestHelper {

	private EncodeTestHelper() {}

	static DecodeContext toDecodeContext(
			EncodeResult result, long rowCount, io.github.dfa1.vortex.core.DType dtype,
			EncodingRegistry registry
	) {
		List<ByteBuffer> buffers = result.buffers();
		MemorySegment[] segments = new MemorySegment[buffers.size()];
		for (int i = 0; i < buffers.size(); i++) {
			segments[i] = MemorySegment.ofBuffer(buffers.get(i).rewind());
		}
		ArrayNode root = toArrayNode(result.rootNode());
		return new DecodeContext(root, dtype, rowCount, segments, registry, Arena.ofAuto());
	}

	private static ArrayNode toArrayNode(EncodeNode enc) {
		ArrayNode[] children = new ArrayNode[enc.children().length];
		for (int i = 0; i < children.length; i++) {
			children[i] = toArrayNode(enc.children()[i]);
		}
		return new ArrayNode(enc.encodingId(), enc.metadata(), children, enc.bufferIndices(), null);
	}
}
