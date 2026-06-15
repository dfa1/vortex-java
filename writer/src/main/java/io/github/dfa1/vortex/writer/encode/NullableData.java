package io.github.dfa1.vortex.writer.encode;

/// Writer-side carrier for nullable column data: a packed values array paired with a
/// per-row validity bitmap.
///
/// <p>{@code values} carries the raw storage in the shape an [Encoding] expects
/// ({@code int[]}, {@code long[]}, {@code byte[]}, {@code double[]}, ...). Null
/// positions hold zero-valued placeholders so primitive encoders can compress them
/// alongside real data. {@code validity} has the same logical length: {@code true}
/// for valid rows, {@code false} for nulls.
///
/// <p>The writer recognises this shape and emits the {@code vortex.masked}
/// wire layout: a non-nullable child (the storage) plus an optional Bool
/// validity child. Readers reconstruct a [io.github.dfa1.vortex.reader.array.MaskedArray].
///
/// <p>Invariant: {@code values} length (or storage row count) matches
/// {@code validity.length}; placeholders at null positions must be zero so the
/// the validity child contains no semantic nulls.
///
/// @param values   packed values array; concrete type depends on the column dtype
/// @param validity per-row validity bitmap, parallel to {@code values}
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record NullableData(Object values, boolean[] validity) {
}
