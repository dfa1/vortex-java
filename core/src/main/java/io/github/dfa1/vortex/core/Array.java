package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.core.array.EmptyArray;

import java.lang.foreign.MemorySegment;

/// Decoded columnar data. Concrete subtypes in [io.github.dfa1.vortex.core.array]
/// specialise element access for the JIT; each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
///
/// Not declared `sealed` because the concrete implementations live in a
/// sub-package (`core.array`) and the project does not declare a JPMS module —
/// JLS only allows cross-package `permits` inside the same named module. The
/// effective hierarchy is still closed: only types in [io.github.dfa1.vortex.core.array]
/// should implement this interface.
public interface Array {

	long length();

	DType dtype();

	default ArrayStats stats() {
		return ArrayStats.empty();
	}

	default MemorySegment buffer(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no raw buffers");
	}

	default Array child(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no children");
	}

	static Array empty(DType dtype) {
		return new EmptyArray(dtype);
	}
}
