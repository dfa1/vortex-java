package io.github.dfa1.vortex.writer.encode;

/// Writer-side carrier for nullable column data: a packed values array paired with a
/// per-row validity bitmap.
///
/// `values` carries the raw storage in the shape an [Encoding] expects
/// (`int[]`, `long[]`, `byte[]`, `double[]`, ...). Null
/// positions hold zero-valued placeholders so primitive encoders can compress them
/// alongside real data. `validity` has the same logical length: `true`
/// for valid rows, `false` for nulls.
///
/// The writer recognizes this shape and emits the `vortex.masked`
/// wire layout: a non-nullable child (the storage) plus an optional Bool
/// validity child. Readers reconstruct a [io.github.dfa1.vortex.reader.array.MaskedArray].
///
/// Invariant: `values` length (or storage row count) matches
/// `validity.length`; placeholders at null positions must be zero so the
/// the validity child contains no semantic nulls.
///
/// @param values   packed values array; concrete type depends on the column dtype
/// @param validity per-row validity bitmap, parallel to `values`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record NullableData(Object values, boolean[] validity) {
}
