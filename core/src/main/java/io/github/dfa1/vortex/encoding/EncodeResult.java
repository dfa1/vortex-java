package io.github.dfa1.vortex.encoding;

import java.nio.ByteBuffer;
import java.util.List;

/// Output of encoding an array to bytes for one flat segment.
public record EncodeResult(
		EncodeNode rootNode,
		List<ByteBuffer> buffers,
		byte[] statsMin,
		byte[] statsMax
) {
	/// Convenience factory for single-buffer leaf encodings.
	public static EncodeResult simple(EncodingId encodingId, ByteBuffer data, byte[] min, byte[] max) {
		return new EncodeResult(EncodeNode.leaf(encodingId, 0), List.of(data), min, max);
	}

	public static EncodeResult simple(EncodingId encodingId, ByteBuffer data) {
		return simple(encodingId, data, null, null);
	}

	public boolean hasStats() {
		return statsMin != null && statsMax != null;
	}
}
