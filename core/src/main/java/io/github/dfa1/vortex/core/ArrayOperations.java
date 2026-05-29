package io.github.dfa1.vortex.core;

import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/// Primitive accessors for a decoded column. Concrete [Array] subtypes override
/// only the accessors that make sense for their dtype; everything else stays at
/// the default `throw VortexException(...)` so unsupported calls fail with clear
/// attribution.
///
/// Bulk `forEachXxx` variants are provided as defaults that loop over `getXxx`,
/// but specialised subtypes (e.g. [io.github.dfa1.vortex.core.array.LongArray])
/// override them to hoist length and ValueLayout for the JIT.
public interface ArrayOperations {

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

	private VortexException unsupported(String op) {
		return new VortexException(getClass().getSimpleName() + " does not support " + op);
	}
}
