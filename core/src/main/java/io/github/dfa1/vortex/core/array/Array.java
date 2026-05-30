package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/// Decoded columnar data. Concrete subtypes specialise element access for the JIT;
/// each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
public sealed interface Array
        permits BoolArray, ByteArray, DoubleArray, EmptyArray, Float16Array, FloatArray,
                GenericArray, IntArray, LongArray, NullArray, ShortArray, StructArray,
                VarBinArray {

	long length();

	DType dtype();

	default boolean getBoolean(long i) {
		throw unsupported("getBoolean");
	}

	default byte getByte(long i) {
		throw unsupported("getByte");
	}

	default short getShort(long i) {
		throw unsupported("getShort");
	}

	default int getInt(long i) {
		throw unsupported("getInt");
	}

	default long getLong(long i) {
		throw unsupported("getLong");
	}

	default float getFloat(long i) {
		throw unsupported("getFloat");
	}

	default double getDouble(long i) {
		throw unsupported("getDouble");
	}

	default byte[] getBytes(long i) {
		throw unsupported("getBytes");
	}

	default int getByteLength(long i) {
		return getBytes(i).length;
	}

	default void forEachInt(IntConsumer c) {
		long n = length();
		for (long i = 0; i < n; i++) {
			c.accept(getInt(i));
		}
	}

	default void forEachLong(LongConsumer c) {
		long n = length();
		for (long i = 0; i < n; i++) {
			c.accept(getLong(i));
		}
	}

	default void forEachDouble(DoubleConsumer c) {
		long n = length();
		for (long i = 0; i < n; i++) {
			c.accept(getDouble(i));
		}
	}

	default void forEachByteLength(IntConsumer c) {
		long n = length();
		for (long i = 0; i < n; i++) {
			c.accept(getByteLength(i));
		}
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

	static Array empty(DType dtype) {
		return new EmptyArray(dtype);
	}

	private VortexException unsupported(String op) {
		return new VortexException(getClass().getSimpleName() + " does not support " + op);
	}
}
