package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.core.array.GenericArray;

import java.lang.foreign.MemorySegment;

/// Decoded columnar data. Concrete subtypes specialise element access for the
/// JIT; [GenericArray] is the legacy multi-buffer fallback used by codecs that
/// haven't been migrated yet.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
///
/// Not declared `sealed` because the concrete implementations live in a
/// sub-package (`core.array`) and the project does not declare a JPMS module —
/// JLS only allows cross-package `permits` inside the same named module. The
/// effective hierarchy is still closed: only types in [io.github.dfa1.vortex.core.array]
/// should implement this interface.
public interface Array extends ArrayOperations {

	Array[] NO_CHILDREN = new Array[0];

	static Array empty(DType dtype) {
		return new GenericArray(dtype, 0, new MemorySegment[0], NO_CHILDREN, ArrayStats.empty());
	}

	default ArrayStats stats() {
		return ArrayStats.empty();
	}

	default MemorySegment buffer(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no raw buffers");
	}

	default Array child(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no children");
	}
}
