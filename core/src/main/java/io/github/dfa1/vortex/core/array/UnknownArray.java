package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Opaque passthrough array for encodings unknown to this reader.
///
/// Holds raw on-disk bytes: encoding id, metadata, buffers, child arrays (themselves
/// unknown), and stats. Children of an unknown node are always wrapped unknown — matches
/// Rust `decode_foreign` in `vortex-array/src/serde.rs`.
///
/// Constructed by `EncodingRegistry` when `allowUnknown()` is set and an encoding id is not
/// in the registry. Data access beyond `buffer(i)` and `child(i)` is not supported.
public record UnknownArray(
		String encodingId,
		DType dtype,
		long length,
		ByteBuffer metadata,
		MemorySegment[] buffers,
		Array[] children,
		ArrayStats stats
) implements Array {

	@Override
	public MemorySegment buffer(int i) {
		return buffers[i];
	}

	@Override
	public Array child(int i) {
		return children[i];
	}
}
