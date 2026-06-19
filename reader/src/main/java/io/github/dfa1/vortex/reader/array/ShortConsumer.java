package io.github.dfa1.vortex.reader.array;

/// Consumer of `short` values. JDK has `IntConsumer` / `LongConsumer`
/// but no narrow-primitive variants, so this fills the gap for
/// [ShortArray#forEachShort(ShortConsumer)].
@FunctionalInterface
public interface ShortConsumer {

    /// Accepts a short value.
    ///
    /// @param value the short value to consume
    void accept(short value);
}
