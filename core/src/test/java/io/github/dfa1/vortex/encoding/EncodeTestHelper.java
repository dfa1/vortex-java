package io.github.dfa1.vortex.encoding;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/// Converts an [EncodeResult] into a [DecodeContext] for roundtrip tests.
final class EncodeTestHelper {

	private EncodeTestHelper() {
		// no instances
	}

	static DecodeContext toDecodeContext(
			EncodeResult result, long rowCount, io.github.dfa1.vortex.core.DType dtype,
			EncodingRegistry registry
	) {
		List<MemorySegment> buffers = result.buffers();
		MemorySegment[] segments = buffers.toArray(new MemorySegment[0]);
		ArrayNode root = toArrayNode(result.rootNode());
		return new DecodeContext(root, dtype, rowCount, segments, registry, Arena.ofAuto());
	}

	private static ArrayNode toArrayNode(EncodeNode enc) {
		ArrayNode[] children = new ArrayNode[enc.children().length];
		for (int i = 0; i < children.length; i++) {
			children[i] = toArrayNode(enc.children()[i]);
		}
		return ArrayNode.of(enc.encodingId(), enc.metadata(), children, enc.bufferIndices(), null);
	}
}
