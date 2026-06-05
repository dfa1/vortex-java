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
                FloatArray, GenericArray, IntArray, ListArray, ListViewArray, LongArray,
                MaskedArray, NullArray, ShortArray, StructArray, UnknownArray, VarBinArray {

	/// Returns the number of elements in this array.
	///
	/// @return element count
	long length();

	/// Returns the logical type of elements in this array.
	///
	/// @return dtype
	DType dtype();

	/// Returns the raw buffer at position {@code i} (used by some {@link io.github.dfa1.vortex.encoding.Encoding}).
	///
	/// @param i buffer index
	/// @return memory segment for buffer {@code i}
	default MemorySegment buffer(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no raw buffers");
	}

	/// Returns the child array at position {@code i} (used by some {@link io.github.dfa1.vortex.encoding.Encoding}).
	///
	/// @param i child index
	/// @return child array at index {@code i}
	default Array child(int i) {
		throw new VortexException(getClass().getSimpleName() + " has no children");
	}

}
