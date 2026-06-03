package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;

/// Decoded columnar data. Concrete subtypes specialise element access for the JIT;
/// each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
public sealed interface Array
        permits BoolArray, ByteArray, DoubleArray, EmptyArray, FixedSizeListArray, Float16Array,
                FloatArray, GenericArray, IntArray, ListArray, ListViewArray, LongArray, MaskedArray,
                NullArray, ShortArray, StructArray, VarBinArray {

	long length();

	DType dtype();

	/// Optional method to access the internals (used by some {@link io.github.dfa1.vortex.encoding.Encoding})
	default MemorySegment buffer(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no raw buffers");
	}

	/// Optional method to access any children array (used by some {@link io.github.dfa1.vortex.encoding.Encoding})
	default Array child(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no children");
	}

}
