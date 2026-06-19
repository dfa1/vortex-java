package io.github.dfa1.vortex.reader.array;

/// Consumer of `boolean` values. Used by [BoolArray#forEachBoolean(BooleanConsumer)].
@FunctionalInterface
public interface BooleanConsumer {

    /// Accepts a boolean value.
    ///
    /// @param value the boolean value to consume
    void accept(boolean value);
}
