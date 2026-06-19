package io.github.dfa1.vortex.reader.array;

/// Consumer of `byte` values. Used by [ByteArray#forEachByte(ByteConsumer)].
@FunctionalInterface
public interface ByteConsumer {

    /// Accepts a byte value.
    ///
    /// @param value the byte value to consume
    void accept(byte value);
}
