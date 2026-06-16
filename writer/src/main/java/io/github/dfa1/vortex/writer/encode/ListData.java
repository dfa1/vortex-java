package io.github.dfa1.vortex.writer.encode;

/// Input data for encoding a `vortex.list` column.
///
/// `elements` is the flat array of all inner values concatenated.
/// `offsets` has length `outerLen + 1`; `offsets[i]..offsets[i+1]`
/// is the range of elements for list `i`. `offsets[0]` must be 0.
///
/// @param elements flat array of all concatenated inner values
/// @param offsets  offset array of length `outerLen + 1`; `offsets[0]` must be 0
/// @param outerLen number of outer list elements
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ListData(Object elements, long[] offsets, long outerLen) {
}
